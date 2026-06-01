package com.truholdem.service.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.service.GameTurnTimeoutService;
import com.truholdem.service.PokerGameService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClusterFailoverService — take over orphaned tables")
class ClusterFailoverServiceTest {

    @Mock
    private TableOwnershipService ownership;
    @Mock
    private PokerGameService pokerGameService;
    @Mock
    private GameTurnTimeoutService turnTimeoutService;
    @Mock
    private Game game;

    private ClusterFailoverService failover;
    private final UUID gameId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCluster().setOwnershipEnabled(true);
        props.getCluster().setTakeoverEnabled(true);
        failover = new ClusterFailoverService(props, ownership, pokerGameService, turnTimeoutService);
    }

    @Test
    @DisplayName("orphaned active table is claimed and its turn timer resumed")
    void resumesOrphanedTable() {
        when(ownership.currentOwner(gameId)).thenReturn(null); // owner died
        when(ownership.acquire(gameId)).thenReturn(true);
        when(game.isFinished()).thenReturn(false);
        when(pokerGameService.getGame(gameId)).thenReturn(Optional.of(game));

        assertThat(failover.takeOverIfOrphaned(gameId)).isTrue();
        verify(turnTimeoutService).scheduleForCurrentTurn(game);
    }

    @Test
    @DisplayName("a table that still has a live owner is left alone")
    void skipsOwnedTable() {
        when(ownership.currentOwner(gameId)).thenReturn("node-B");

        assertThat(failover.takeOverIfOrphaned(gameId)).isFalse();
        verify(ownership, never()).acquire(gameId);
        verify(turnTimeoutService, never()).scheduleForCurrentTurn(game);
    }

    @Test
    @DisplayName("losing the acquire race means no takeover")
    void skipsWhenAcquireRaceLost() {
        when(ownership.currentOwner(gameId)).thenReturn(null);
        when(ownership.acquire(gameId)).thenReturn(false);

        assertThat(failover.takeOverIfOrphaned(gameId)).isFalse();
        verify(turnTimeoutService, never()).scheduleForCurrentTurn(game);
    }

    @Test
    @DisplayName("a finished/missing game is pruned from the active set instead of resumed")
    void prunesFinishedGame() {
        when(ownership.currentOwner(gameId)).thenReturn(null);
        when(ownership.acquire(gameId)).thenReturn(true);
        when(pokerGameService.getGame(gameId)).thenReturn(Optional.empty());

        assertThat(failover.takeOverIfOrphaned(gameId)).isFalse();
        verify(ownership).release(gameId);
        verify(turnTimeoutService, never()).scheduleForCurrentTurn(game);
    }
}
