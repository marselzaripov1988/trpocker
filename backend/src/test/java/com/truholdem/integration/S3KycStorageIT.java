package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.service.wallet.storage.KycStorage;
import com.truholdem.service.wallet.storage.S3KycStorage;

/** Round-trips KYC media through a real MinIO (S3-compatible) container, exercising SigV4 + path-style S3. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("KYC S3/MinIO object storage")
class S3KycStorageIT {

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void s3Props(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.kyc-storage-type", () -> "s3");
        registry.add("app.payments.s3-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.payments.s3-bucket", () -> "kyc");
        registry.add("app.payments.s3-region", () -> "us-east-1");
        registry.add("app.payments.s3-access-key", () -> "minioadmin");
        registry.add("app.payments.s3-secret-key", () -> "minioadmin");
    }

    @Autowired
    private KycStorage storage;

    @Test
    @DisplayName("store → load → delete round-trips against MinIO (S3 backend selected)")
    void roundTrip() {
        assertThat(storage).isInstanceOf(S3KycStorage.class);
        String key = UUID.randomUUID().toString();
        byte[] data = "encrypted-kyc-video-bytes".getBytes(StandardCharsets.UTF_8);

        storage.store(key, data);
        assertThat(storage.load(key)).isEqualTo(data);

        storage.delete(key);
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(RuntimeException.class); // 404 NoSuchKey
    }
}
