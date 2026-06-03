package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.repository.KycDocumentRepository;
import com.truholdem.service.wallet.KycVerificationService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.kyc-storage-dir=target/kyc-enc-it",
        "app.payments.kyc-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.payments.kyc-retention-days=30" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("KYC at-rest encryption + retention/erasure")
class KycEncryptionRetentionIT {

    private static final Path STORAGE = Path.of("target/kyc-enc-it");

    @Autowired
    private KycVerificationService kyc;
    @Autowired
    private KycDocumentRepository documentRepository;

    @BeforeEach
    void setUp() throws Exception {
        documentRepository.deleteAll();
        if (Files.exists(STORAGE)) {
            try (var paths = Files.walk(STORAGE)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static byte[] fakeVideo() {
        byte[] b = new byte[2048];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    @Test
    @DisplayName("on-disk bytes are encrypted but reads return the original plaintext")
    void encryptedAtRest() throws Exception {
        UUID user = UUID.randomUUID();
        byte[] video = fakeVideo();

        kyc.submitVerificationVideo(user, video, "passport.mp4", "video/mp4");

        var doc = documentRepository.findFirstByUserIdOrderByUploadedAtDesc(user).orElseThrow();
        assertThat(doc.isEncrypted()).isTrue();

        byte[] onDisk = Files.readAllBytes(STORAGE.resolve(doc.getStorageKey()));
        assertThat(onDisk).as("ciphertext differs from plaintext").isNotEqualTo(video);
        assertThat(onDisk.length).as("12-byte IV + ciphertext + 16-byte GCM tag").isEqualTo(video.length + 28);

        assertThat(kyc.loadLatest(user).orElseThrow().content())
                .as("decrypts back to the original").isEqualTo(video);
    }

    @Test
    @DisplayName("retention purge deletes expired media (file + metadata)")
    void retentionPurge() throws Exception {
        UUID user = UUID.randomUUID();
        kyc.submitVerificationVideo(user, fakeVideo(), "passport.mp4", "video/mp4");
        var doc = documentRepository.findFirstByUserIdOrderByUploadedAtDesc(user).orElseThrow();
        Path file = STORAGE.resolve(doc.getStorageKey());
        assertThat(file).exists();

        int purged = kyc.purgeOlderThan(Instant.now().plus(1, ChronoUnit.DAYS)); // cutoff in the future → all

        assertThat(purged).isEqualTo(1);
        assertThat(documentRepository.count()).isZero();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("GDPR erasure removes all of a user's KYC media")
    void erasure() throws Exception {
        UUID user = UUID.randomUUID();
        kyc.submitVerificationVideo(user, fakeVideo(), "a.mp4", "video/mp4");
        kyc.submitVerificationVideo(user, fakeVideo(), "b.mp4", "video/mp4");

        int erased = kyc.eraseForUser(user);

        assertThat(erased).isEqualTo(2);
        assertThat(documentRepository.findByUserId(user)).isEmpty();
    }
}
