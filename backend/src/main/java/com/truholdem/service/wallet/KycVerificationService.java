package com.truholdem.service.wallet;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.KycPendingDto;
import com.truholdem.dto.wallet.KycReEncryptResult;
import com.truholdem.model.KycDocument;
import com.truholdem.model.KycStatus;
import com.truholdem.repository.KycDocumentRepository;
import com.truholdem.repository.KycRecordRepository;
import com.truholdem.service.wallet.WalletExceptions.PaymentsDisabledException;
import com.truholdem.service.wallet.crypto.KycCrypto;
import com.truholdem.service.wallet.storage.KycStorage;

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
    private final KycRecordRepository kycRecordRepository;
    private final WalletService walletService;
    private final AppProperties appProperties;
    private final KycStorage storage;
    private final KycKeyProvider keyProvider;
    private final com.truholdem.service.wallet.av.KycAvScanner avScanner;

    public KycVerificationService(KycDocumentRepository documentRepository,
            KycRecordRepository kycRecordRepository, WalletService walletService,
            AppProperties appProperties, KycStorage storage, KycKeyProvider keyProvider,
            com.truholdem.service.wallet.av.KycAvScanner avScanner) {
        this.documentRepository = documentRepository;
        this.kycRecordRepository = kycRecordRepository;
        this.walletService = walletService;
        this.appProperties = appProperties;
        this.storage = storage;
        this.keyProvider = keyProvider;
        this.avScanner = avScanner;
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
        avScanner.scan(content); // rejects infected uploads (no-op unless AV scanning is enabled)

        String sha = sha256Hex(content); // over the plaintext, before any encryption
        Optional<KycKeyProvider.DataKey> dataKey = keyProvider.newDataKey();
        byte[] toStore = dataKey
                .map(dk -> KycCrypto.encrypt(content, dk.key()))
                .orElse(content);
        Optional<String> keyId = dataKey.map(KycKeyProvider.DataKey::keyId);

        String storageKey = UUID.randomUUID().toString();
        storage.store(storageKey, toStore);

        documentRepository.save(new KycDocument(userId, sanitize(originalFilename), contentType,
                content.length, sha, storageKey, dataKey.isPresent(), keyId.orElse(null)));
        KycStatus status = walletService.submitKyc(userId); // → PENDING (unless already VERIFIED)
        log.info("KYC verification video stored for user {} ({} bytes, {}, keyId={})",
                userId, content.length, contentType, keyId.orElse("none"));
        return status;
    }

    /** Load the user's most recent KYC document (for ADMIN review), decrypting if it was stored encrypted. */
    @Transactional(readOnly = true)
    public Optional<LoadedDocument> loadLatest(UUID userId) {
        return documentRepository.findFirstByUserIdOrderByUploadedAtDesc(userId).map(doc -> {
            byte[] bytes = storage.load(doc.getStorageKey());
            if (doc.isEncrypted()) {
                String keyId = doc.getEncryptionKeyId() != null
                        ? doc.getEncryptionKeyId() : ConfigKycKeyProvider.LEGACY_KEY_ID;
                bytes = KycCrypto.decrypt(bytes, keyProvider.resolveKey(keyId));
            }
            return new LoadedDocument(bytes, doc.getContentType(), doc.getOriginalFilename());
        });
    }

    /**
     * Re-encrypt every KYC document under the currently-active key/provider. Use after rotating config keys
     * (re-keys documents off retired keys so the old keys can be dropped) or when migrating to KMS (the active
     * KMS provider re-wraps each document with a fresh data key). A document is skipped if it is already on the
     * active key, or if it is encrypted but encryption is now disabled (never silently downgrade to plaintext).
     * Documents whose existing key id the active provider cannot resolve are decrypted via the config keyring
     * as a fallback (covers config→KMS, where old ids are keyring ids).
     */
    @Transactional
    public KycReEncryptResult reEncryptAll() {
        List<KycDocument> all = documentRepository.findAll();
        int reEncrypted = 0;
        int skipped = 0;
        for (KycDocument doc : all) {
            Optional<KycKeyProvider.DataKey> target = keyProvider.newDataKey();
            if (doc.isEncrypted() && target.isEmpty()) {
                skipped++; // encryption disabled now — do not downgrade existing ciphertext to plaintext
                continue;
            }
            if (doc.isEncrypted()
                    && target.map(t -> t.keyId().equals(doc.getEncryptionKeyId())).orElse(false)) {
                skipped++; // already on the active key (config keyring); KMS ids are unique so never match
                continue;
            }
            byte[] blob = storage.load(doc.getStorageKey());
            byte[] plaintext = doc.isEncrypted()
                    ? KycCrypto.decrypt(blob, resolveExisting(doc.getEncryptionKeyId()))
                    : blob;
            byte[] toStore = target.map(t -> KycCrypto.encrypt(plaintext, t.key())).orElse(plaintext);
            storage.store(doc.getStorageKey(), toStore); // overwrite in place (same storage key)
            doc.applyReEncryption(target.isPresent(), target.map(KycKeyProvider.DataKey::keyId).orElse(null));
            documentRepository.save(doc);
            reEncrypted++;
        }
        log.info("KYC re-encryption: re-encrypted {}, skipped {} of {} document(s)",
                reEncrypted, skipped, all.size());
        return new KycReEncryptResult(reEncrypted, skipped, all.size());
    }

    /** Resolve a document's existing key, falling back to the config keyring when the active provider (e.g.
     *  KMS) cannot resolve a legacy keyring id. */
    private byte[] resolveExisting(String keyId) {
        try {
            return keyProvider.resolveKey(keyId);
        } catch (RuntimeException primary) {
            try {
                return new ConfigKycKeyProvider(appProperties).resolveKey(keyId);
            } catch (RuntimeException fallbackFailed) {
                throw primary;
            }
        }
    }

    /** Pending KYC submissions (status PENDING with an uploaded document) awaiting moderator review. */
    @Transactional(readOnly = true)
    public List<KycPendingDto> listPending() {
        return kycRecordRepository.findByStatusOrderByUpdatedAtAsc(KycStatus.PENDING).stream()
                .flatMap(record -> documentRepository
                        .findFirstByUserIdOrderByUploadedAtDesc(record.getUserId())
                        .map(doc -> new KycPendingDto(record.getUserId(), record.getStatus(),
                                doc.getUploadedAt(), doc.getOriginalFilename(), doc.getContentType(),
                                doc.getSizeBytes()))
                        .stream())
                .toList();
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
            storage.delete(doc.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("Failed to delete KYC blob for {} — removing the DB row anyway", doc.getId(), e);
        }
        documentRepository.delete(doc);
    }

    /** Retention cutoff = now minus the configured retention days (or empty if retention is disabled). */
    Optional<Instant> retentionCutoff() {
        int days = appProperties.getPayments().getKycRetentionDays();
        return days > 0 ? Optional.of(Instant.now().minus(days, ChronoUnit.DAYS)) : Optional.empty();
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
