package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.PyramidBuyout;
import com.truholdem.repository.PyramidBuyoutRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("PyramidBuyout persistence + uniqueness (one per player, one per seat)")
class PyramidBuyoutRepositoryIT {

    @Autowired
    private PyramidBuyoutRepository repository;

    private UUID tournament;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        tournament = UUID.randomUUID();
    }

    private PyramidBuyout buyout(UUID player, int level, int seatIndex) {
        return new PyramidBuyout(tournament, player, level, seatIndex,
                new BigDecimal("50"), CryptoAsset.USDT_TRC20);
    }

    @Test
    @DisplayName("persists and is queryable by tournament + buyer")
    void persistAndQuery() {
        UUID player = UUID.randomUUID();
        repository.saveAndFlush(buyout(player, 2, 3));

        assertThat(repository.findByTournamentId(tournament)).hasSize(1);
        assertThat(repository.findByTournamentIdAndBuyerPlayerId(tournament, player)).isPresent();
        assertThat(repository.existsByTournamentIdAndLevelAndSeatIndex(tournament, 2, 3)).isTrue();
        assertThat(repository.countByTournamentId(tournament)).isEqualTo(1);
        assertThat(repository.findByTournamentId(tournament).get(0).getPriceAmount())
                .isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("a player can buy at most one seat (unique per player)")
    void onePerPlayer() {
        UUID player = UUID.randomUUID();
        repository.saveAndFlush(buyout(player, 2, 1));
        assertThatThrownBy(() -> repository.saveAndFlush(buyout(player, 2, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a seat can be bought by only one player (unique per seat)")
    void onePerSeat() {
        repository.saveAndFlush(buyout(UUID.randomUUID(), 2, 5));
        assertThatThrownBy(() -> repository.saveAndFlush(buyout(UUID.randomUUID(), 2, 5)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
