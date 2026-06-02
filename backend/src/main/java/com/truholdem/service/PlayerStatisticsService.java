package com.truholdem.service;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerStatistics;
import com.truholdem.repository.PlayerStatisticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Service
@Transactional
public class PlayerStatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerStatisticsService.class);
    private static final int MIN_HANDS_FOR_LEADERBOARD = 10;

    private final PlayerStatisticsRepository statsRepository;
    private final AppProperties appProperties;
    private final ConcurrentHashMap<String, BufferedActions> actionBuffer = new ConcurrentHashMap<>();

    public PlayerStatisticsService(PlayerStatisticsRepository statsRepository, AppProperties appProperties) {
        this.statsRepository = statsRepository;
        this.appProperties = appProperties;
    }

    

    
    public PlayerStatistics getOrCreateStats(String playerName) {
        return statsRepository.findFirstByPlayerName(playerName)
            .orElseGet(() -> {
                PlayerStatistics newStats = new PlayerStatistics(playerName);
                logger.info("Created new statistics for player: {}", playerName);
                return statsRepository.save(newStats);
            });
    }

    
    public PlayerStatistics getOrCreateStats(UUID userId, String playerName) {
        return statsRepository.findByUserId(userId)
            .orElseGet(() -> {
                PlayerStatistics newStats = new PlayerStatistics(userId, playerName);
                logger.info("Created new statistics for user: {}", userId);
                return statsRepository.save(newStats);
            });
    }

    

    
    @Transactional(readOnly = true)
    public Optional<PlayerStatistics> getStatsByName(String playerName) {
        return statsRepository.findFirstByPlayerName(playerName);
    }

    
    @Transactional(readOnly = true)
    public Optional<PlayerStatistics> getStatsByUserId(UUID userId) {
        return statsRepository.findByUserId(userId);
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> searchPlayers(String nameQuery) {
        return statsRepository.findByPlayerNameContainingIgnoreCase(nameQuery);
    }

    

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getTopByHandsWon() {
        return statsRepository.findTop10ByOrderByHandsWonDesc();
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getTopByWinnings() {
        return statsRepository.findTop10ByOrderByTotalWinningsDesc();
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getTopByBiggestPot() {
        return statsRepository.findTop10ByOrderByBiggestPotWonDesc();
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getTopByWinStreak() {
        return statsRepository.findTop10ByOrderByLongestWinStreakDesc();
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getTopByWinRate() {
        return statsRepository.findTopPlayersByWinRate(MIN_HANDS_FOR_LEADERBOARD);
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getMostActive() {
        return statsRepository.findTop20ByOrderByHandsPlayedDesc();
    }

    
    @Transactional(readOnly = true)
    public List<PlayerStatistics> getRecentlyActive() {
        return statsRepository.findTop20ByOrderByLastHandPlayedDesc();
    }

    

    
    @Transactional(readOnly = true)
    public LeaderboardData getLeaderboard() {
        return new LeaderboardData(
            getTopByWinnings(),
            getTopByHandsWon(),
            getTopByWinRate(),
            getTopByBiggestPot(),
            getTopByWinStreak(),
            getMostActive()
        );
    }

    public record LeaderboardData(
        List<PlayerStatistics> byWinnings,
        List<PlayerStatistics> byHandsWon,
        List<PlayerStatistics> byWinRate,
        List<PlayerStatistics> byBiggestPot,
        List<PlayerStatistics> byWinStreak,
        List<PlayerStatistics> mostActive
    ) {}

    

    
    @Transactional(readOnly = true)
    public PlayerStatsSummary getStatsSummary(String playerName) {
        Optional<PlayerStatistics> optStats = statsRepository.findFirstByPlayerName(playerName);
        if (optStats.isEmpty()) {
            return null;
        }

        PlayerStatistics stats = optStats.get();
        return new PlayerStatsSummary(
            stats.getPlayerName(),
            stats.getHandsPlayed(),
            stats.getHandsWon(),
            stats.getWinRate(),
            stats.getNetProfit(),
            stats.getVPIP(),
            stats.getPFR(),
            stats.getAggressionFactor(),
            stats.getWTSD(),
            stats.getWonAtShowdown(),
            stats.getBiggestPotWon(),
            stats.getLongestWinStreak(),
            stats.getTotalSessions()
        );
    }

    public record PlayerStatsSummary(
        String playerName,
        int handsPlayed,
        int handsWon,
        double winRate,
        java.math.BigDecimal netProfit,
        double vpip,
        double pfr,
        double aggressionFactor,
        double wtsd,
        double wonAtShowdown,
        int biggestPotWon,
        int longestWinStreak,
        int totalSessions
    ) {}

    

    
    public void recordHandPlayed(String playerName, boolean voluntarilyPutIn, boolean raisedPreFlop) {
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.recordHandPlayed(voluntarilyPutIn, raisedPreFlop);
        statsRepository.save(stats);
    }

    
    public void recordAction(String playerName, String action) {
        if (shouldBufferActions()) {
            actionBuffer.computeIfAbsent(playerName, ignored -> new BufferedActions()).recordAction(action);
            return;
        }
        PlayerStatistics stats = getOrCreateStats(playerName);
        applyAction(stats, action);
        statsRepository.save(stats);
    }

    
    public void recordAllIn(String playerName) {
        if (shouldBufferActions()) {
            actionBuffer.computeIfAbsent(playerName, ignored -> new BufferedActions()).allIn = true;
            return;
        }
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.recordAllIn();
        statsRepository.save(stats);
    }

    /**
     * Flushes per-action statistics buffered during the current hand.
     */
    public void flushBufferedActionsForGame(Game game) {
        if (!shouldBufferActions()) {
            return;
        }
        for (Player player : game.getPlayers()) {
            BufferedActions buffered = actionBuffer.remove(player.getName());
            if (buffered == null) {
                continue;
            }
            PlayerStatistics stats = getOrCreateStats(player.getName());
            buffered.applyTo(stats);
            statsRepository.save(stats);
        }
    }

    private boolean shouldBufferActions() {
        return appProperties.getGame().isBufferStatisticsOnActions();
    }

    private void applyAction(PlayerStatistics stats, String action) {
        switch (action.toUpperCase()) {
            case "BET" -> stats.recordBet();
            case "RAISE" -> stats.recordRaise();
            case "CALL" -> stats.recordCall();
            case "FOLD" -> stats.recordFold();
            case "CHECK" -> stats.recordCheck();
            default -> { }
        }
    }

    private static final class BufferedActions {
        private int bets;
        private int raises;
        private int calls;
        private int folds;
        private int checks;
        private boolean allIn;

        void recordAction(String action) {
            switch (action.toUpperCase()) {
                case "BET" -> bets++;
                case "RAISE" -> raises++;
                case "CALL" -> calls++;
                case "FOLD" -> folds++;
                case "CHECK" -> checks++;
                default -> { }
            }
        }

        void applyTo(PlayerStatistics stats) {
            for (int i = 0; i < bets; i++) {
                stats.recordBet();
            }
            for (int i = 0; i < raises; i++) {
                stats.recordRaise();
            }
            for (int i = 0; i < calls; i++) {
                stats.recordCall();
            }
            for (int i = 0; i < folds; i++) {
                stats.recordFold();
            }
            for (int i = 0; i < checks; i++) {
                stats.recordCheck();
            }
            if (allIn) {
                stats.recordAllIn();
            }
        }
    }

    
    public void recordShowdown(String playerName, boolean won) {
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.recordShowdown(won);
        statsRepository.save(stats);
    }

    
    public void recordWin(String playerName, int potAmount) {
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.recordWin(potAmount);
        statsRepository.save(stats);
        logger.debug("Recorded win for {}: {} chips", playerName, potAmount);
    }

    
    public void recordLoss(String playerName, int amountLost) {
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.recordLoss(amountLost);
        statsRepository.save(stats);
    }

    
    public void recordAllInResult(String playerName, boolean won) {
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.recordAllInResult(won);
        statsRepository.save(stats);
    }

    
    public void startSession(String playerName) {
        PlayerStatistics stats = getOrCreateStats(playerName);
        stats.startNewSession();
        statsRepository.save(stats);
    }
}
