package com.truholdem.service.tournament;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.truholdem.config.AppProperties;
import com.truholdem.model.TournamentTable;
import com.truholdem.repository.TournamentTableRepository;

@Service
public class TournamentTableShardService {

    private final AppProperties appProperties;
    private final TournamentTableRepository tableRepository;

    public TournamentTableShardService(AppProperties appProperties, TournamentTableRepository tableRepository) {
        this.appProperties = appProperties;
        this.tableRepository = tableRepository;
    }

    public int resolveShard(int tableNumber) {
        int shardCount = Math.max(1, appProperties.getTournament().getShardCount());
        return Math.floorMod(tableNumber - 1, shardCount);
    }

    public String tableTopic(UUID tournamentId, int tableNumber) {
        return "/topic/tournament/" + tournamentId + "/table/" + tableNumber;
    }

    public String shardTopic(UUID tournamentId, int tableNumber) {
        int shard = resolveShard(tableNumber);
        return "/topic/tournament/" + tournamentId + "/shard/" + shard;
    }

    public Optional<String> tableTopicForGame(UUID gameId) {
        if (!appProperties.getTournament().isTableTopicsEnabled() || gameId == null) {
            return Optional.empty();
        }
        return tableRepository.findByCurrentGameId(gameId)
                .map(table -> tableTopic(table.getTournament().getId(), table.getTableNumber()));
    }

    public Optional<String> shardTopicForGame(UUID gameId) {
        if (!appProperties.getTournament().isTableTopicsEnabled() || gameId == null) {
            return Optional.empty();
        }
        return tableRepository.findByCurrentGameId(gameId)
                .map(table -> shardTopic(table.getTournament().getId(), table.getTableNumber()));
    }

    public Optional<TournamentTable> findTableForGame(UUID gameId) {
        if (gameId == null) {
            return Optional.empty();
        }
        return tableRepository.findByCurrentGameId(gameId);
    }
}
