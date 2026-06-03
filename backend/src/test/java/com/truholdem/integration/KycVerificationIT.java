package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
import com.truholdem.model.KycStatus;
import com.truholdem.repository.KycDocumentRepository;
import com.truholdem.service.wallet.KycVerificationService;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.kyc-storage-dir=target/kyc-it",
        "app.payments.kyc-max-upload-bytes=1048576" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("KYC verification video upload")
class KycVerificationIT {

    private static final Path STORAGE = Path.of("target/kyc-it");

    @Autowired
    private KycVerificationService kyc;
    @Autowired
    private KycDocumentRepository documentRepository;
    @Autowired
    private WalletService walletService;

    @BeforeEach
    void setUp() throws Exception {
        documentRepository.deleteAll();
        if (Files.exists(STORAGE)) {
            try (var paths = Files.walk(STORAGE)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static byte[] fakeVideo(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    @Test
    @DisplayName("a video upload is stored on disk + metadata in DB and moves KYC to PENDING")
    void storesVideoAndMovesToPending() throws Exception {
        UUID user = UUID.randomUUID();
        byte[] video = fakeVideo(4096);

        KycStatus status = kyc.submitVerificationVideo(user, video, "passport.mp4", "video/mp4");

        assertThat(status).isEqualTo(KycStatus.PENDING);
        assertThat(walletService.kycStatus(user)).isEqualTo(KycStatus.PENDING);

        var doc = documentRepository.findFirstByUserIdOrderByUploadedAtDesc(user).orElseThrow();
        assertThat(doc.getContentType()).isEqualTo("video/mp4");
        assertThat(doc.getSizeBytes()).isEqualTo(4096);
        assertThat(doc.getOriginalFilename()).isEqualTo("passport.mp4");
        // bytes are on disk under the storage key, not in the DB
        assertThat(Files.readAllBytes(STORAGE.resolve(doc.getStorageKey()))).isEqualTo(video);

        // moderator can load it back intact
        var loaded = kyc.loadLatest(user).orElseThrow();
        assertThat(loaded.content()).isEqualTo(video);
        assertThat(loaded.contentType()).isEqualTo("video/mp4");
    }

    @Test
    @DisplayName("pending submissions are listed with document metadata")
    void listsPending() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        kyc.submitVerificationVideo(u1, fakeVideo(1024), "a.mp4", "video/mp4");
        kyc.submitVerificationVideo(u2, fakeVideo(2048), "b.webm", "video/webm");

        var pending = kyc.listPending();

        assertThat(pending).extracting(p -> p.userId()).contains(u1, u2);
        assertThat(pending).allSatisfy(p -> assertThat(p.status()).isEqualTo(KycStatus.PENDING));
    }

    @Test
    @DisplayName("a non-video upload is rejected")
    void rejectsNonVideo() {
        assertThatThrownBy(() -> kyc.submitVerificationVideo(
                UUID.randomUUID(), "hi".getBytes(StandardCharsets.UTF_8), "doc.pdf", "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an oversized upload is rejected (max 1 MB in this test)")
    void rejectsOversized() {
        assertThatThrownBy(() -> kyc.submitVerificationVideo(
                UUID.randomUUID(), fakeVideo(1048577), "big.mp4", "video/mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty upload is rejected")
    void rejectsEmpty() {
        assertThatThrownBy(() -> kyc.submitVerificationVideo(
                UUID.randomUUID(), new byte[0], "empty.mp4", "video/mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
