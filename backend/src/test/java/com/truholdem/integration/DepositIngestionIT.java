package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true", "app.payments.min-confirmations=2" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Watch-only deposit detection (address → user → credit)")
class DepositIngestionIT {

    private static final CryptoAsset ASSET = CryptoAsset.ETH;
    private static final byte[] SEED = "ingestion-it-seed".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DepositAddressPoolService pool;
    @Autowired
    private DepositIngestionService ingestion;
    @Autowired
    private WalletService walletService;
    @Autowired
    private DepositAddressPoolRepository repository;

    private UUID user;
    private String assignedAddress;
    private String freeAddress;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        List<PoolEntryDto> entries = OfflineDepositPoolGenerator.generate(SEED, ASSET, 3).publicEntries().stream()
                .map(e -> new PoolEntryDto(e.asset(), e.derivationIndex(), e.address())).toList();
        pool.importBatch(entries);
        user = UUID.randomUUID();
        assignedAddress = pool.allocate(user, ASSET); // claims the lowest-index address for this user
        freeAddress = entries.stream().map(PoolEntryDto::address)
                .filter(a -> !a.equals(assignedAddress)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a confirmed deposit to an assigned address credits its owner")
    void creditsOwnerOfAssignedAddress() {
        Result result = ingestion.ingest(ASSET, assignedAddress, "tx-credit", new BigDecimal("5"), 2);

        assertThat(result).isEqualTo(Result.CREDITED);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("re-notifying the same tx does not double-credit")
    void idempotentByTxId() {
        ingestion.ingest(ASSET, assignedAddress, "tx-idem", new BigDecimal("5"), 2);
        Result second = ingestion.ingest(ASSET, assignedAddress, "tx-idem", new BigDecimal("5"), 5);

        assertThat(second).isEqualTo(Result.DUPLICATE);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a deposit below the confirmation threshold is withheld")
    void pendingBelowConfirmations() {
        Result result = ingestion.ingest(ASSET, assignedAddress, "tx-pending", new BigDecimal("3"), 1); // min is 2

        assertThat(result).isEqualTo(Result.PENDING_CONFIRMATIONS);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a deposit to an unassigned (FREE) or unknown address is ignored")
    void ignoresUnassignedAddress() {
        Result free = ingestion.ingest(ASSET, freeAddress, "tx-free", new BigDecimal("9"), 5);
        Result unknown = ingestion.ingest(ASSET, "0x000000000000000000000000000000000000dEaD",
                "tx-unknown", new BigDecimal("9"), 5);

        assertThat(free).isEqualTo(Result.UNKNOWN_ADDRESS);
        assertThat(unknown).isEqualTo(Result.UNKNOWN_ADDRESS);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("0");
    }
}
