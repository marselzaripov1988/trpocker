package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import com.truholdem.dto.wallet.PoolEntryDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.service.wallet.DepositAddressPoolService;
import com.truholdem.service.wallet.DepositIngestionService;
import com.truholdem.service.wallet.DepositIngestionService.Result;
import com.truholdem.service.wallet.WalletService;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * USDT-on-Solana deposit ingestion: offline-generated USDT ATAs import as base58 pool addresses, allocate
 * one-per-user, and a confirmed deposit detected at that address credits its owner (the ingestion path is
 * network-agnostic). Also asserts the pool import rejects a malformed Solana address.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true", "app.payments.min-confirmations=1" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("USDT-Solana deposit ingestion (ATA → user → credit)")
class DepositSolanaIngestionIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_SOL;
    private static final byte[] SEED = "sol-ingestion-it-seed".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DepositAddressPoolService pool;
    @Autowired
    private DepositIngestionService ingestion;
    @Autowired
    private WalletService walletService;
    @Autowired
    private DepositAddressPoolRepository repository;

    private UUID user;
    private String ata;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        List<PoolEntryDto> entries = OfflineDepositPoolGenerator.generate(SEED, ASSET, 3).publicEntries().stream()
                .map(e -> new PoolEntryDto(e.asset(), e.derivationIndex(), e.address())).toList();
        pool.importBatch(entries); // succeeds → SPL addresses pass validation
        user = UUID.randomUUID();
        ata = pool.allocate(user, ASSET);
    }

    @Test
    @DisplayName("a confirmed USDT deposit to an assigned ATA credits its owner")
    void creditsOwnerOfAssignedAta() {
        Result result = ingestion.ingest(ASSET, ata, "sol-tx-1", new BigDecimal("250"), 1);
        assertThat(result).isEqualTo(Result.CREDITED);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("import rejects a malformed Solana address")
    void rejectsMalformedAddress() {
        assertThatThrownBy(() -> pool.importBatch(List.of(new PoolEntryDto(ASSET, 99, "not-valid-0OIl"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
