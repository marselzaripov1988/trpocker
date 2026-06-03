package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
import com.truholdem.service.wallet.CryptoPaymentProvider;
import com.truholdem.service.wallet.DepositAddressPoolService;
import com.truholdem.service.wallet.OfflinePoolCryptoPaymentProvider;
import com.truholdem.tools.OfflineDepositPoolGenerator;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true", "app.payments.provider=offline-pool" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("provider=offline-pool wiring")
class OfflinePoolProviderIT {

    @Autowired
    private CryptoPaymentProvider provider;
    @Autowired
    private DepositAddressPoolService pool;
    @Autowired
    private DepositAddressPoolRepository repository;

    @Test
    @DisplayName("offline-pool is the active provider and allocateDepositAddress serves from the pool")
    void wiringAndAllocate() {
        assertThat(provider).isInstanceOf(OfflinePoolCryptoPaymentProvider.class);

        repository.deleteAll();
        var batch = OfflineDepositPoolGenerator.generate(
                "wiring-it-seed".getBytes(StandardCharsets.UTF_8), CryptoAsset.ETH, 1);
        var e = batch.publicEntries().get(0);
        pool.importBatch(java.util.List.of(new PoolEntryDto(e.asset(), e.derivationIndex(), e.address())));

        String address = provider.allocateDepositAddress(UUID.randomUUID(), CryptoAsset.ETH);
        assertThat(address).isEqualTo(e.address());
    }
}
