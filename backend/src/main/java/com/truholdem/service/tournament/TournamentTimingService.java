package com.truholdem.service.tournament;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Tournament;

/**
 * Resolves blind level timing (supports test override via {@code app.tournament.level-duration-seconds}).
 */
@Service
public class TournamentTimingService {

    private final AppProperties.Tournament tournamentProperties;

    public TournamentTimingService(AppProperties appProperties) {
        this.tournamentProperties = appProperties.getTournament();
    }

    public Duration levelDuration(Tournament tournament) {
        int overrideSeconds = tournamentProperties.getLevelDurationSeconds();
        if (overrideSeconds > 0) {
            return Duration.ofSeconds(overrideSeconds);
        }
        return Duration.ofMinutes(tournament.getBlindStructure().getLevelDurationMinutes());
    }

    public Instant levelEndTime(Tournament tournament) {
        if (tournament.getLevelStartTime() == null || !tournament.getStatus().isPlayable()) {
            return null;
        }
        return tournament.getLevelStartTime().plus(levelDuration(tournament));
    }

    public long secondsToNextLevel(Tournament tournament) {
        if (tournament.getLevelStartTime() == null || !tournament.getStatus().isPlayable()) {
            return 0;
        }
        long elapsed = Duration.between(tournament.getLevelStartTime(), Instant.now()).toSeconds();
        long total = levelDuration(tournament).toSeconds();
        return Math.max(0, total - elapsed);
    }
}
