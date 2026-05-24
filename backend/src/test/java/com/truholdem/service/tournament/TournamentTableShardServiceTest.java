package com.truholdem.service.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.truholdem.config.AppProperties;
import com.truholdem.repository.TournamentTableRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentTableShardService Tests")
class TournamentTableShardServiceTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppProperties.Tournament tournamentConfig;

    @Mock
    private TournamentTableRepository tableRepository;

    private TournamentTableShardService shardService;

    @BeforeEach
    void setUp() {
        when(appProperties.getTournament()).thenReturn(tournamentConfig);
        when(tournamentConfig.getShardCount()).thenReturn(16);
        shardService = new TournamentTableShardService(appProperties, tableRepository);
    }

    @Test
    @DisplayName("resolveShard maps table numbers across shard count")
    void resolveShard_distributesTables() {
        assertThat(shardService.resolveShard(1)).isZero();
        assertThat(shardService.resolveShard(16)).isEqualTo(15);
        assertThat(shardService.resolveShard(17)).isZero();
    }

    @Test
    @DisplayName("tableTopic and shardTopic follow predictable paths")
    void topics_areStable() {
        UUID tournamentId = UUID.randomUUID();

        assertThat(shardService.tableTopic(tournamentId, 3))
                .isEqualTo("/topic/tournament/" + tournamentId + "/table/3");
        assertThat(shardService.shardTopic(tournamentId, 3))
                .isEqualTo("/topic/tournament/" + tournamentId + "/shard/2");
    }
}
