package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.wallet.PoolEntryDto;
import com.truholdem.dto.wallet.PoolImportRequest;
import com.truholdem.dto.wallet.PoolImportResponse;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.service.wallet.DepositAddressPoolService;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * End-to-end deposit-address-pool import: the offline generator writes addresses.json, that exact file
 * deserialises into the admin import body, the service imports it, and a user is allocated one of the
 * imported addresses. Proves the offline export format is wire-compatible with the import endpoint.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Deposit-address pool import (offline file → DB → allocate)")
class DepositPoolImportIT {

    private static final String SEED_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    @Autowired
    private DepositAddressPoolService pool;
    @Autowired
    private DepositAddressPoolRepository repository;

    @Test
    @DisplayName("addresses.json from the generator imports into the DB and allocates to a user")
    void importsGeneratedFileEndToEnd(@TempDir Path dir) throws Exception {
        repository.deleteAll();

        // 1. OFFLINE: generate a batch → writes private.json (kept offline) + addresses.json (for the server).
        OfflineDepositPoolGenerator.main(new String[] {
                "--asset=ETH", "--count=5", "--seed-hex=" + SEED_HEX, "--out-dir=" + dir });

        Path addressesJson = dir.resolve("addresses.json");
        Path privateJson = dir.resolve("private.json");
        assertThat(addressesJson).exists();
        assertThat(privateJson).exists();

        String publicFile = Files.readString(addressesJson);
        System.out.println("=== addresses.json (goes to the server) ===\n" + publicFile);
        System.out.println("=== private.json head (stays OFFLINE) ===\n"
                + Files.readString(privateJson).substring(0, 220));

        // The public file carries NO secrets; the private file does.
        assertThat(publicFile).doesNotContain("privateKeyHex").doesNotContain("seedHex");
        assertThat(Files.readString(privateJson)).contains("seedHex").contains("privateKeyHex");

        // 2. The server receives addresses.json verbatim as the admin import body (PoolImportRequest).
        PoolImportRequest request = new ObjectMapper().readValue(publicFile, PoolImportRequest.class);
        assertThat(request.addresses()).hasSize(5);
        assertThat(request.addresses()).allSatisfy(e -> {
            assertThat(e.asset()).isEqualTo(CryptoAsset.ETH);
            assertThat(e.address()).startsWith("0x");
        });

        // 3. IMPORT → rows persisted as FREE.
        PoolImportResponse result = pool.importBatch(request.addresses());
        assertThat(result.imported()).isEqualTo(5);
        assertThat(result.skipped()).isZero();
        assertThat(repository.countByAssetAndStatus(CryptoAsset.ETH, DepositAddressStatus.FREE)).isEqualTo(5);

        // 4. Re-import is idempotent (dedup by asset+address).
        assertThat(pool.importBatch(request.addresses()).skipped()).isEqualTo(5);
        assertThat(repository.count()).isEqualTo(5);

        // 5. ALLOCATE: a user gets one of the imported addresses; it flips FREE → ASSIGNED.
        String allocated = pool.allocate(UUID.randomUUID(), CryptoAsset.ETH);
        assertThat(request.addresses().stream().map(PoolEntryDto::address)).contains(allocated);
        assertThat(repository.countByAssetAndStatus(CryptoAsset.ETH, DepositAddressStatus.FREE)).isEqualTo(4);
        assertThat(repository.countByAssetAndStatus(CryptoAsset.ETH, DepositAddressStatus.ASSIGNED)).isEqualTo(1);
    }
}
