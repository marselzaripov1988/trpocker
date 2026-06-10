package com.truholdem.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tournament house commission (fee) on the crypto prize pool")
class TournamentFeeTest {

    private static Tournament realMoney(int feeBasisPoints, String buyIn, int players) {
        Tournament t = Tournament.builder("RM")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .feeBasisPoints(feeBasisPoints)
                .build();
        t.setCryptoBuyInAmount(new BigDecimal(buyIn));
        t.setCryptoBuyInAsset(CryptoAsset.USDT_TRC20);
        for (int i = 0; i < players; i++) {
            t.registerPlayer(UUID.randomUUID(), "p" + i);
        }
        return t;
    }

    @Test
    @DisplayName("default 0% fee → prize pool equals the gross pool, no house cut")
    void zeroFeeIsUnchanged() {
        Tournament t = realMoney(0, "100", 4);
        assertThat(t.getFeeBasisPoints()).isZero();
        assertThat(t.cryptoGrossPool()).isEqualByComparingTo("400");
        assertThat(t.cryptoHouseFee()).isEqualByComparingTo("0");
        assertThat(t.cryptoPrizePool()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("10% fee → house takes 10% off the gross, winners split the remainder")
    void tenPercentFee() {
        Tournament t = realMoney(1000, "100", 4); // gross = 100 × 4 = 400
        assertThat(t.cryptoGrossPool()).isEqualByComparingTo("400");
        assertThat(t.cryptoHouseFee()).isEqualByComparingTo("40");
        assertThat(t.cryptoPrizePool()).isEqualByComparingTo("360");
        // Default payout structure 50/30/20 applies to the NET pool.
        assertThat(t.cryptoPrizeForPosition(1)).isEqualByComparingTo("180"); // 50% of 360
        assertThat(t.cryptoPrizeForPosition(2)).isEqualByComparingTo("108"); // 30% of 360
        assertThat(t.cryptoPrizeForPosition(3)).isEqualByComparingTo("72");  // 20% of 360
    }

    @Test
    @DisplayName("gross = fee + prize pool always reconciles (no money created or lost)")
    void feePlusPrizeReconciles() {
        Tournament t = realMoney(1500, "33", 7); // gross = 33 × 7 = 231, fee = 15%
        assertThat(t.cryptoHouseFee().add(t.cryptoPrizePool()))
                .isEqualByComparingTo(t.cryptoGrossPool());
    }

    @Test
    @DisplayName("20% is the maximum fee")
    void maxFeeIsTwentyPercent() {
        Tournament t = realMoney(2000, "100", 5); // gross 500
        assertThat(t.cryptoHouseFee()).isEqualByComparingTo("100"); // 20% of 500
        assertThat(t.cryptoPrizePool()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("a fee above 20% is rejected (setter + builder)")
    void feeAboveCapRejected() {
        Tournament t = new Tournament("X", TournamentType.FREEZEOUT);
        assertThatThrownBy(() -> t.setFeeBasisPoints(2001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.setFeeBasisPoints(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.builder("Y").feeBasisPoints(5000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("play-money tournament has no pool and no fee, whatever the rate")
    void playMoneyHasNoFee() {
        Tournament t = Tournament.builder("Play")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .feeBasisPoints(1000)
                .build();
        t.registerPlayer(UUID.randomUUID(), "p1");
        t.registerPlayer(UUID.randomUUID(), "p2");
        assertThat(t.isRealMoney()).isFalse();
        assertThat(t.cryptoGrossPool()).isEqualByComparingTo("0");
        assertThat(t.cryptoHouseFee()).isEqualByComparingTo("0");
        assertThat(t.cryptoPrizePool()).isEqualByComparingTo("0");
    }
}
