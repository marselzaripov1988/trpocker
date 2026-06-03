package com.truholdem.service.wallet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

        String storageKey = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(storageKey), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store KYC document", e);
        }

        documentRepository.save(new KycDocument(userId, sanitize(originalFilename), contentType,
                content.length, sha256Hex(content), storageKey));
        KycStatus status = walletService.submitKyc(userId); // → PENDING (unless already VERIFIED)
        log.info("KYC verification video stored for user {} ({} bytes, {})", userId, content.length, contentType);
        return status;
    }

    /** Load the user's most recent KYC document (for ADMIN review). */
    @Transactional(readOnly = true)
    public Optional<LoadedDocument> loadLatest(UUID userId) {
        return documentRepository.findFirstByUserIdOrderByUploadedAtDesc(userId).map(doc -> {
            try {
                byte[] bytes = Files.readAllBytes(storageDir().resolve(doc.getStorageKey()));
                return new LoadedDocument(bytes, doc.getContentType(), doc.getOriginalFilename());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read KYC document " + doc.getId(), e);
            }
        });
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
