package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.wallet.PoolEntryDto;
import com.truholdem.dto.wallet.PoolImportResponse;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.service.wallet.DepositAddressPoolService;
import com.truholdem.service.wallet.WalletExceptions.DepositAddressPoolExhaustedException;
import com.truholdem.tools.OfflineDepositPoolGenerator;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Offline deposit-address pool (import + allocate)")
class DepositAddressPoolServiceIT {

    private static final byte[] SEED = "truholdem-pool-it-seed".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DepositAddressPoolService pool;
    @Autowired
    private DepositAddressPoolRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    /** Public entries from the offline generator, tagged with the given asset (ETH-family share addresses). */
    private List<PoolEntryDto> entries(CryptoAsset asset, int count) {
        return OfflineDepositPoolGenerator.generate(SEED, asset, count).publicEntries().stream()
                .map(e -> new PoolEntryDto(e.asset(), e.derivationIndex(), e.address())).toList();
    }

    @Test
    @DisplayName("allocate hands out a free address and is idempotent per (user, asset)")
    void allocateIsIdempotent() {
        pool.importBatch(entries(CryptoAsset.ETH, 3));
        UUID user = UUID.randomUUID();

        String first = pool.allocate(user, CryptoAsset.ETH);
        String again = pool.allocate(user, CryptoAsset.ETH);

        assertThat(first).startsWith("0x");
        assertThat(again).as("same address on repeat — no second address consumed").isEqualTo(first);
        assertThat(repository.countByAssetAndStatus(CryptoAsset.ETH,
                com.truholdem.model.DepositAddressStatus.FREE)).isEqualTo(2);
    }

    @Test
    @DisplayName("different users get different addresses")
    void distinctPerUser() {
        pool.importBatch(entries(CryptoAsset.ETH, 3));

        String a = pool.allocate(UUID.randomUUID(), CryptoAsset.ETH);
        String b = pool.allocate(UUID.randomUUID(), CryptoAsset.ETH);

        assertThat(a).isNotEqualTo(b);
        assertThat(repository.countByAssetAndStatus(CryptoAsset.ETH,
                com.truholdem.model.DepositAddressStatus.FREE)).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty pool for the asset is rejected, not silently double-assigned")
    void exhaustionRejected() {
        pool.importBatch(entries(CryptoAsset.ETH, 1));
        pool.allocate(UUID.randomUUID(), CryptoAsset.ETH);

        assertThatThrownBy(() -> pool.allocate(UUID.randomUUID(), CryptoAsset.ETH))
                .isInstanceOf(DepositAddressPoolExhaustedException.class);
    }

    @Test
    @DisplayName("allocation for one asset does not consume another asset's addresses")
    void assetIsolation() {
        pool.importBatch(entries(CryptoAsset.ETH, 2));
        pool.importBatch(entries(CryptoAsset.USDT_ERC20, 2));

        pool.allocate(UUID.randomUUID(), CryptoAsset.ETH);

        assertThat(repository.countByAssetAndStatus(CryptoAsset.USDT_ERC20,
                com.truholdem.model.DepositAddressStatus.FREE)).as("USDT pool untouched").isEqualTo(2);
        assertThat(repository.countByAssetAndStatus(CryptoAsset.ETH,
                com.truholdem.model.DepositAddressStatus.FREE)).isEqualTo(1);
    }

    @Test
    @DisplayName("re-importing the same batch skips duplicates (idempotent)")
    void importDedup() {
        List<PoolEntryDto> batch = entries(CryptoAsset.ETH, 4);

        PoolImportResponse first = pool.importBatch(batch);
        PoolImportResponse second = pool.importBatch(batch);

        assertThat(first.imported()).isEqualTo(4);
        assertThat(first.skipped()).isZero();
        assertThat(second.imported()).isZero();
        assertThat(second.skipped()).isEqualTo(4);
    }

    @Test
    @DisplayName("an ETH address with a wrong EIP-55 checksum is rejected on import")
    void importRejectsBadChecksum() {
        String valid = entries(CryptoAsset.ETH, 1).get(0).address();
        String tampered = flipFirstLetterCase(valid);

        assertThatThrownBy(() -> pool.importBatch(List.of(new PoolEntryDto(CryptoAsset.ETH, 0, tampered))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TRC-20 (TRON) addresses import and allocate like ETH-family")
    void tronImportAndAllocate() {
        pool.importBatch(entries(CryptoAsset.USDT_TRC20, 2));

        String address = pool.allocate(UUID.randomUUID(), CryptoAsset.USDT_TRC20);

        assertThat(address).startsWith("T");
        assertThat(repository.countByAssetAndStatus(CryptoAsset.USDT_TRC20,
                com.truholdem.model.DepositAddressStatus.FREE)).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed TRON address is rejected on import")
    void importRejectsBadTronAddress() {
        assertThatThrownBy(() -> pool.importBatch(
                List.of(new PoolEntryDto(CryptoAsset.USDT_TRC20, 0, "TNotARealTronAddress000000000000000"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BTC (P2PKH) addresses import and allocate")
    void btcImportAndAllocate() {
        pool.importBatch(entries(CryptoAsset.BTC, 2));

        String address = pool.allocate(UUID.randomUUID(), CryptoAsset.BTC);

        assertThat(address).startsWith("1");
        assertThat(repository.countByAssetAndStatus(CryptoAsset.BTC,
                com.truholdem.model.DepositAddressStatus.FREE)).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed Bitcoin address is rejected on import")
    void importRejectsBadBtcAddress() {
        assertThatThrownBy(() -> pool.importBatch(
                List.of(new PoolEntryDto(CryptoAsset.BTC, 0, "1NotARealBitcoinAddress0000000000"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("status reports free/assigned counts per asset")
    void statusCounts() {
        pool.importBatch(entries(CryptoAsset.ETH, 3));
        pool.allocate(UUID.randomUUID(), CryptoAsset.ETH);

        var eth = pool.status().assets().stream().filter(a -> a.asset() == CryptoAsset.ETH).findFirst().orElseThrow();
        assertThat(eth.free()).isEqualTo(2);
        assertThat(eth.assigned()).isEqualTo(1);
    }

    /** Flip the case of the first hex letter → guaranteed-invalid EIP-55 casing (original was the unique valid). */
    private static String flipFirstLetterCase(String address) {
        char[] c = address.toCharArray();
        for (int i = 2; i < c.length; i++) {
            if (Character.isLetter(c[i])) {
                c[i] = Character.isUpperCase(c[i]) ? Character.toLowerCase(c[i]) : Character.toUpperCase(c[i]);
                return new String(c);
            }
        }
        return address;
    }
}
