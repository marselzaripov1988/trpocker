package com.truholdem.service.wallet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.model.KycDocument;
import com.truholdem.model.KycStatus;
import com.truholdem.repository.KycDocumentRepository;
import com.truholdem.service.wallet.WalletExceptions.PaymentsDisabledException;
import com.truholdem.service.wallet.crypto.KycCrypto;

/**
 * Handles KYC verification uploads (e.g. a video of the user holding their passport). The file bytes are
 * stored on disk under {@code app.payments.kyc-storage-dir} (keyed by a random name); only metadata is
 * persisted in the DB. Uploading moves the user's KYC to {@code PENDING} for moderator/provider review; the
 * bytes are served only to ADMIN. Inert unless {@code app.payments.enabled}.
 */
@Service
public class KycVerificationService {

    private static final Logger log = LoggerFactory.getLogger(KycVerificationService.class);

    private final KycDocumentRepository documentRepository;
    private final WalletService walletService;
    private final AppProperties appProperties;

    public KycVerificationService(KycDocumentRepository documentRepository, WalletService walletService,
            AppProperties appProperties) {
        this.documentRepository = documentRepository;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** A loaded KYC document for moderator review: the file bytes + how to serve them. */
    public record LoadedDocument(byte[] content, String contentType, String filename) {
    }

    /** Store a verification video for the user and move KYC to PENDING. Validates type (video/*) and size. */
    @Transactional
    public KycStatus submitVerificationVideo(UUID userId, byte[] content, String originalFilename,
            String contentType) {
        if (!appProperties.getPayments().isEnabled()) {
            throw new PaymentsDisabledException();
        }
        Path dir = storageDir();
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("KYC upload is empty");
        }
        long max = appProperties.getPayments().getKycMaxUploadBytes();
        if (content.length > max) {
            throw new IllegalArgumentException("KYC upload exceeds the maximum size of " + max + " bytes");
        }
        if (contentType == null || !contentType.toLowerCase().startsWith("video/")) {
            throw new IllegalArgumentException("KYC verification upload must be a video; got " + contentType);
        }

        String sha = sha256Hex(content); // over the plaintext, before any encryption
        Optional<byte[]> key = encryptionKey();
        byte[] toStore = key.map(k -> KycCrypto.encrypt(content, k)).orElse(content);

        String storageKey = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(storageKey), toStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store KYC document", e);
        }

        documentRepository.save(new KycDocument(userId, sanitize(originalFilename), contentType,
                content.length, sha, storageKey, key.isPresent()));
        KycStatus status = walletService.submitKyc(userId); // → PENDING (unless already VERIFIED)
        log.info("KYC verification video stored for user {} ({} bytes, {}, encrypted={})",
                userId, content.length, contentType, key.isPresent());
        return status;
    }

    /** Load the user's most recent KYC document (for ADMIN review), decrypting if it was stored encrypted. */
    @Transactional(readOnly = true)
    public Optional<LoadedDocument> loadLatest(UUID userId) {
        return documentRepository.findFirstByUserIdOrderByUploadedAtDesc(userId).map(doc -> {
            try {
                byte[] bytes = Files.readAllBytes(storageDir().resolve(doc.getStorageKey()));
                if (doc.isEncrypted()) {
                    byte[] key = encryptionKey().orElseThrow(() -> new IllegalStateException(
                            "KYC document " + doc.getId() + " is encrypted but no key is configured"));
                    bytes = KycCrypto.decrypt(bytes, key);
                }
                return new LoadedDocument(bytes, doc.getContentType(), doc.getOriginalFilename());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read KYC document " + doc.getId(), e);
            }
        });
    }

    /** GDPR retention: delete KYC media (file + metadata) uploaded before {@code cutoff}. Returns the count. */
    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        List<KycDocument> expired = documentRepository.findByUploadedAtBefore(cutoff);
        expired.forEach(this::deleteDocument);
        if (!expired.isEmpty()) {
            log.info("KYC retention: purged {} document(s) older than {}", expired.size(), cutoff);
        }
        return expired.size();
    }

    /** GDPR right-to-erasure: delete all KYC media for a user (file + metadata). Returns the count. */
    @Transactional
    public int eraseForUser(UUID userId) {
        List<KycDocument> docs = documentRepository.findByUserId(userId);
        docs.forEach(this::deleteDocument);
        log.info("KYC erasure: removed {} document(s) for user {}", docs.size(), userId);
        return docs.size();
    }

    private void deleteDocument(KycDocument doc) {
        try {
            Files.deleteIfExists(storageDir().resolve(doc.getStorageKey()));
        } catch (IOException e) {
            log.warn("Failed to delete KYC file for {} — removing the DB row anyway", doc.getId(), e);
        }
        documentRepository.delete(doc);
    }

    /** Configured AES key bytes for at-rest encryption, or empty if not set. */
    private Optional<byte[]> encryptionKey() {
        String b64 = appProperties.getPayments().getKycEncryptionKey();
        if (b64 == null || b64.isBlank()) {
            return Optional.empty();
        }
        byte[] key = Base64.getDecoder().decode(b64.trim());
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalStateException("app.payments.kyc-encryption-key must be a base64 AES key of "
                    + "16/24/32 bytes; got " + key.length);
        }
        return Optional.of(key);
    }

    /** Retention cutoff = now minus the configured retention days (or empty if retention is disabled). */
    Optional<Instant> retentionCutoff() {
        int days = appProperties.getPayments().getKycRetentionDays();
        return days > 0 ? Optional.of(Instant.now().minus(days, ChronoUnit.DAYS)) : Optional.empty();
    }

    private Path storageDir() {
        String dir = appProperties.getPayments().getKycStorageDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("KYC storage directory is not configured (app.payments.kyc-storage-dir)");
        }
        return Path.of(dir);
    }

    /** Never trust the client filename for the filesystem — strip path separators and cap length. */
    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "kyc-video";
        }
        String cleaned = filename.replaceAll("[\\\\/\\r\\n]", "_");
        return cleaned.length() > 256 ? cleaned.substring(0, 256) : cleaned;
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
