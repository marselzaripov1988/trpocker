package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.truholdem.dto.HouseRevenueResponse;
import com.truholdem.dto.HouseRevenueResponse.AssetRevenue;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.CashRakeEntryRepository;
import com.truholdem.repository.TournamentFeeEntryRepository;

@ExtendWith(MockitoExtension.class)
class HouseRevenueServiceTest {

    @Mock
    private TournamentFeeEntryRepository tournamentFees;
    @Mock
    private CashRakeEntryRepository cashRake;

    private HouseRevenueService service;

    @BeforeEach
    void setUp() {
        service = new HouseRevenueService(tournamentFees, cashRake);
        // Default everything to zero; individual tests override the assets they care about.
        lenient().when(tournamentFees.totalFeeForAsset(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(cashRake.totalRakeForAsset(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void aggregatesFeeAndRakePerAssetAndSumsTotal() {
        when(tournamentFees.totalFeeForAsset(CryptoAsset.USDT_TRC20)).thenReturn(new BigDecimal("8"));
        when(cashRake.totalRakeForAsset(CryptoAsset.USDT_TRC20)).thenReturn(new BigDecimal("2.5"));

        HouseRevenueResponse summary = service.summary();

        AssetRevenue usdt = summary.assets().stream()
                .filter(a -> a.asset().equals("USDT_TRC20")).findFirst().orElseThrow();
        assertThat(usdt.tournamentFees()).isEqualByComparingTo("8");
        assertThat(usdt.cashRake()).isEqualByComparingTo("2.5");
        assertThat(usdt.total()).isEqualByComparingTo("10.5");
    }

    @Test
    void omitsAssetsWithNoRevenue() {
        when(tournamentFees.totalFeeForAsset(CryptoAsset.BTC)).thenReturn(new BigDecimal("0.1"));

        HouseRevenueResponse summary = service.summary();

        assertThat(summary.assets()).extracting(AssetRevenue::asset).containsExactly("BTC");
    }

    @Test
    void nullSumsAreTreatedAsZero() {
        when(tournamentFees.totalFeeForAsset(any())).thenReturn(null);
        when(cashRake.totalRakeForAsset(any())).thenReturn(null);

        HouseRevenueResponse summary = service.summary();

        assertThat(summary.assets()).isEmpty();
    }
}
