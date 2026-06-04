package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
import com.truholdem.dto.wallet.KycReEncryptResult;
import com.truholdem.model.KycDocument;
import com.truholdem.repository.KycDocumentRepository;
import com.truholdem.service.wallet.KycKeyProvider;
import com.truholdem.service.wallet.KycVerificationService;
import com.truholdem.service.wallet.crypto.KycCrypto;
import com.truholdem.service.wallet.storage.KycStorage;

/**
 * Key rotation: new uploads are encrypted with the active key id (recorded on the document), and a document
 * encrypted with an older key id still decrypts as long as that key stays in the keyring.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.kyc-storage-dir=target/kyc-rot-it",
        "app.payments.kyc-encryption-keys.k1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.payments.kyc-encryption-keys.k2=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=",
        "app.payments.kyc-active-key-id=k2" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("KYC encryption key rotation (key id per document)")
class KycKeyRotationIT {

    private static final Path STORAGE = Path.of("target/kyc-rot-it");

    @Autowired
    private KycVerificationService kyc;
    @Autowired
    private KycDocumentRepository documentRepository;
    @Autowired
    private KycKeyProvider keyProvider;
    @Autowired
    private KycStorage storage;

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
        byte[] b = new byte[1024];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    @Test
    @DisplayName("new upload records the active key id and round-trips")
    void newUploadUsesActiveKey() {
        UUID user = UUID.randomUUID();
        byte[] video = fakeVideo();

        kyc.submitVerificationVideo(user, video, "passport.mp4", "video/mp4");

        KycDocument doc = documentRepository.findFirstByUserIdOrderByUploadedAtDesc(user).orElseThrow();
        assertThat(doc.isEncrypted()).isTrue();
        assertThat(doc.getEncryptionKeyId()).isEqualTo("k2");
        assertThat(kyc.loadLatest(user).orElseThrow().content()).isEqualTo(video);
    }

    @Test
    @DisplayName("a document encrypted with an older key id still decrypts after the active key changed")
    void olderKeyStillDecrypts() {
        UUID user = UUID.randomUUID();
        byte[] video = fakeVideo();

        // Simulate a document written while "k1" was the active key (before rotation to "k2").
        String storageKey = UUID.randomUUID().toString();
        storage.store(storageKey, KycCrypto.encrypt(video, keyProvider.resolveKey("k1")));
        documentRepository.save(new KycDocument(user, "old.mp4", "video/mp4",
                video.length, "sha", storageKey, true, "k1"));

        assertThat(kyc.loadLatest(user).orElseThrow().content())
                .as("decrypted with the recorded older key id").isEqualTo(video);
    }

    @Test
    @DisplayName("re-encryption migrates old-key docs onto the active key, decrypts, and is idempotent")
    void reEncryptMigratesToActiveKey() {
        UUID user = UUID.randomUUID();
        byte[] video = fakeVideo();

        String storageKey = UUID.randomUUID().toString();
        byte[] k1Blob = KycCrypto.encrypt(video, keyProvider.resolveKey("k1"));
        storage.store(storageKey, k1Blob);
        documentRepository.save(new KycDocument(user, "old.mp4", "video/mp4",
                video.length, "sha", storageKey, true, "k1"));

        KycReEncryptResult first = kyc.reEncryptAll();
        assertThat(first.reEncrypted()).isEqualTo(1);

        KycDocument doc = documentRepository.findFirstByUserIdOrderByUploadedAtDesc(user).orElseThrow();
        assertThat(doc.getEncryptionKeyId()).as("migrated to the active key").isEqualTo("k2");
        assertThat(doc.isEncrypted()).isTrue();
        assertThat(storage.load(storageKey)).as("re-encrypted on disk").isNotEqualTo(k1Blob);
        assertThat(kyc.loadLatest(user).orElseThrow().content()).isEqualTo(video);

        KycReEncryptResult second = kyc.reEncryptAll();
        assertThat(second.reEncrypted()).as("already on the active key → nothing to do").isZero();
        assertThat(second.skipped()).isEqualTo(1);
    }
}
