package com.truholdem.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.HandLifecycleState;

@ExtendWith(MockitoExtension.class)
@DisplayName("GameHandLifecycleService Tests")
class GameHandLifecycleServiceTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppProperties.Game gameProperties;

    @Mock
    private PokerGameService pokerGameService;

    @Mock
    private ScheduledFuture<Object> scheduledFuture;

    private GameHandLifecycleService service;

    @BeforeEach
    void setUp() {
        lenient().doReturn(scheduledFuture)
                .when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        lenient().when(appProperties.getGame()).thenReturn(gameProperties);
        lenient().when(gameProperties.getHandResultDelaySeconds()).thenReturn(3);
        service = new GameHandLifecycleService(taskScheduler, appProperties, pokerGameService);
    }

    @Test
    @DisplayName("Should schedule result delay and next hand transitions")
    void shouldScheduleResultDelayAndNextHandTransitions() {
        UUID gameId = UUID.randomUUID();
        Game completed = game(gameId, HandLifecycleState.HAND_COMPLETED);
        Game delayed = game(gameId, HandLifecycleState.RESULT_DELAY);
        when(pokerGameService.transitionCompletedHandToResultDelay(gameId, completed.getHandNumber()))
                .thenReturn(delayed);

        service.scheduleAfterHandCompleted(completed);
        Runnable resultDelayTransition = captureScheduledRunnable(0);
        resultDelayTransition.run();
        Runnable nextHandTransition = captureScheduledRunnable(1);
        nextHandTransition.run();

        verify(pokerGameService).transitionCompletedHandToResultDelay(gameId, completed.getHandNumber());
        verify(pokerGameService).startNextHandFromLifecycle(gameId, completed.getHandNumber());
    }

    @Test
    @DisplayName("Should not schedule when hand is not completed")
    void shouldNotScheduleWhenHandIsNotCompleted() {
        Game active = game(UUID.randomUUID(), HandLifecycleState.IN_PROGRESS);
        active.setFinished(false);

        service.scheduleAfterHandCompleted(active);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("Should cancel existing transition")
    void shouldCancelExistingTransition() {
        UUID gameId = UUID.randomUUID();
        Game completed = game(gameId, HandLifecycleState.HAND_COMPLETED);
        service.scheduleAfterHandCompleted(completed);

        service.cancel(gameId);

        verify(scheduledFuture).cancel(false);
    }

    private Runnable captureScheduledRunnable(int invocationIndex) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler, atLeast(invocationIndex + 1)).schedule(captor.capture(), any(Instant.class));
        return captor.getAllValues().get(invocationIndex);
    }

    private static Game game(UUID gameId, HandLifecycleState state) {
        Game game = new Game();
        game.setId(gameId);
        game.setHandNumber(7);
        game.setPhase(GamePhase.SHOWDOWN);
        game.setFinished(true);
        game.setHandLifecycleState(state);
        return game;
    }
}
