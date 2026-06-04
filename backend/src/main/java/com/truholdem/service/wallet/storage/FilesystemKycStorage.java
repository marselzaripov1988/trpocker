package com.truholdem.service.wallet.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;

/**
 * KYC media on the local filesystem under {@code app.payments.kyc-storage-dir}. Default backend. In a cluster
 * the directory must be a shared volume so any node can read what another received.
 */
@Component
@ConditionalOnProperty(name = "app.payments.kyc-storage-type", havingValue = "filesystem", matchIfMissing = true)
public class FilesystemKycStorage implements KycStorage {

    private final AppProperties appProperties;

    public FilesystemKycStorage(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void store(String key, byte[] content) {
        Path dir = dir();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(key), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store KYC document", e);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(dir().resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read KYC document " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(dir().resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete KYC document " + key, e);
        }
    }

    private Path dir() {
        String dir = appProperties.getPayments().getKycStorageDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("KYC storage directory is not configured (app.payments.kyc-storage-dir)");
        }
        return Path.of(dir);
    }
}
