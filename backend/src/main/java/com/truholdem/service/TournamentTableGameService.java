package com.truholdem.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.BlindLevel;
import com.truholdem.model.Game;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.TournamentTable;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentTableRepository;

@Service
@Transactional
public class TournamentTableGameService {

    private static final Logger log = LoggerFactory.getLogger(TournamentTableGameService.class);

    private final TournamentTableRepository tableRepository;
    private final TournamentRegistrationRepository registrationRepository;
    private final PokerGameService pokerGameService;

    public TournamentTableGameService(
            TournamentTableRepository tableRepository,
            TournamentRegistrationRepository registrationRepository,
            PokerGameService pokerGameService) {
        this.tableRepository = tableRepository;
        this.registrationRepository = registrationRepository;
        this.pokerGameService = pokerGameService;
    }

    /**
     * Returns the active hand or starts a new one for the tournament table.
     */
    public Game getOrStartTableHand(UUID tournamentId, UUID tableId) {
        TournamentTable table = loadTable(tournamentId, tableId);
        Tournament tournament = table.getTournament();

        if (!tournament.getStatus().isPlayable()) {
            throw new IllegalStateException("Tournament is not in a playable state: " + tournament.getStatus());
        }
        if (!table.isActive()) {
            throw new IllegalStateException("Table is not active");
        }
        if (!table.canStartHand()) {
            throw new IllegalStateException("Not enough players with chips to start a hand");
        }

        Game current = table.getCurrentGame();
        if (current != null) {
            if (!current.isFinished()) {
                return pokerGameService.getGame(current.getId())
                        .orElseGet(() -> attachNewHand(table, tournament));
            }
            Game nextHand = pokerGameService.startNewHand(current.getId());
            table.startNewGame(nextHand);
            tableRepository.save(table);
            log.info("Started new hand {} on tournament table {}", nextHand.getId(), tableId);
            return nextHand;
        }

        return attachNewHand(table, tournament);
    }

    @Transactional(readOnly = true)
    public UUID getCurrentGameId(UUID tournamentId, UUID tableId) {
        TournamentTable table = loadTable(tournamentId, tableId);
        Game current = table.getCurrentGame();
        return current != null ? current.getId() : null;
    }

    private Game attachNewHand(TournamentTable table, Tournament tournament) {
        BlindLevel blinds = tournament.getCurrentBlindLevel();
        List<PlayerInfo> players = buildPlayerInfos(tournament.getId(), table);
        Game game = pokerGameService.createNewGame(
                players,
                blinds.getSmallBlind(),
                blinds.getBigBlind());
        table.startNewGame(game);
        tableRepository.save(table);
        log.info("Created game {} for tournament {} table {}", game.getId(), tournament.getId(), table.getId());
        return game;
    }

    private List<PlayerInfo> buildPlayerInfos(UUID tournamentId, TournamentTable table) {
        List<PlayerInfo> players = new ArrayList<>();
        for (UUID playerId : table.getPlayerIds()) {
            TournamentRegistration registration = registrationRepository
                    .findByTournamentIdAndPlayerId(tournamentId, playerId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Registration not found for player " + playerId));
            if (registration.getCurrentChips() <= 0) {
                continue;
            }
            PlayerInfo info = new PlayerInfo(
                    registration.getPlayerName(),
                    registration.getCurrentChips(),
                    isBotName(registration.getPlayerName()));
            info.setPlayerId(playerId);
            players.add(info);
        }
        if (players.size() < 2) {
            throw new IllegalStateException("Need at least two players with chips to start a hand");
        }
        return players;
    }

    private TournamentTable loadTable(UUID tournamentId, UUID tableId) {
        return tableRepository.findByIdAndTournamentIdWithDetails(tableId, tournamentId)
                .orElseThrow(() -> new ResourceNotFoundException("Table not found: " + tableId));
    }

    private static boolean isBotName(String name) {
        return name != null && name.regionMatches(true, 0, "bot", 0, 3);
    }
}
