package com.truholdem.service.tournament;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.TournamentPlayerEliminated;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.TournamentStatus;
import com.truholdem.model.TournamentTable;
import com.truholdem.model.TournamentType;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.PokerGameService;
import com.truholdem.service.TournamentService;
import com.truholdem.service.TournamentStartService;
import com.truholdem.service.TournamentTableGameService;

/**
 * Pyramid survival: play N hands per table, chip leader advances, re-seat survivors.
 */
@Service
public class PyramidTournamentService {

    private static final Logger log = LoggerFactory.getLogger(PyramidTournamentService.class);
    private static final int MAX_ACTIONS_PER_HAND = 600;

    private final TournamentRepository tournamentRepository;
    private final TournamentRegistrationRepository registrationRepository;
    private final TournamentTableRepository tableRepository;
    private final TournamentTableGameService tableGameService;
    private final PokerGameService pokerGameService;
    private final TournamentService tournamentService;
    private final TournamentStartService tournamentStartService;
    private final AppProperties.Tournament tournamentProperties;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public PyramidTournamentService(
            TournamentRepository tournamentRepository,
            TournamentRegistrationRepository registrationRepository,
            TournamentTableRepository tableRepository,
            TournamentTableGameService tableGameService,
            PokerGameService pokerGameService,
            TournamentService tournamentService,
            TournamentStartService tournamentStartService,
            AppProperties appProperties,
            TransactionTemplate transactionTemplate,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.tournamentRepository = tournamentRepository;
        this.registrationRepository = registrationRepository;
        this.tableRepository = tableRepository;
        this.tableGameService = tableGameService;
        this.pokerGameService = pokerGameService;
        this.tournamentService = tournamentService;
        this.tournamentStartService = tournamentStartService;
        this.tournamentProperties = appProperties.getTournament();
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs pyramid rounds until one champion remains.
     */
    /**
     * Plays one pyramid round (all active tables: hands → table survivors → re-seat).
     */
    public void playCurrentPyramidRound(UUID tournamentId) {
        Tournament tournament = loadTournament(tournamentId);
        assertPyramid(tournament);
        if (!tournament.getStatus().isPlayable() && tournament.getStatus() != TournamentStatus.PAUSED) {
            throw new IllegalStateException("Tournament not playable: " + tournament.getStatus());
        }
        List<TournamentTable> tables = tableRepository.findActiveTablesByTournament(tournamentId);
        if (tables.isEmpty()) {
            throw new IllegalStateException("No active tables for pyramid round " + tournament.getPyramidRound());
        }
        processRoundTables(tournamentId, tables, tournament.getHandsPerRound());
        advanceToNextRound(tournamentId);
        if (registrationRepository.countActiveByTournamentId(tournamentId) == 1) {
            endTournamentInTransaction(tournamentId);
        }
    }

    public PyramidRunResult runToCompletion(UUID tournamentId) {
        Tournament tournament = loadTournament(tournamentId);
        assertPyramid(tournament);

        if (tournament.getStatus() == TournamentStatus.REGISTERING) {
            int registered = registrationRepository.countByTournamentId(tournamentId);
            if (tournamentStartService.shouldStartAsynchronously(registered)) {
                tournamentStartService.completeStart(tournamentId);
            } else {
                tournamentService.startTournament(tournamentId);
            }
            tournament = loadTournament(tournamentId);
        }

        if (!tournament.getStatus().isPlayable()) {
            throw new IllegalStateException("Tournament not playable: " + tournament.getStatus());
        }

        long runStart = System.currentTimeMillis();
        int roundsPlayed = 0;
        int maxRounds = 32;

        while (registrationRepository.countActiveByTournamentId(tournamentId) > 1 && roundsPlayed < maxRounds) {
            tournament = loadTournament(tournamentId);
            List<TournamentTable> tables = tableRepository.findActiveTablesByTournament(tournamentId);
            if (tables.isEmpty()) {
                throw new IllegalStateException("No active tables for pyramid round " + tournament.getPyramidRound());
            }

            int active = registrationRepository.countActiveByTournamentId(tournamentId);
            log.info("Pyramid round {} for tournament {}: {} tables, {} players active",
                    tournament.getPyramidRound(), tournamentId, tables.size(), active);

            long roundStart = System.currentTimeMillis();
            processRoundTables(tournamentId, tables, tournament.getHandsPerRound());
            log.info("Pyramid round {} table play finished in {} ms",
                    tournament.getPyramidRound(), System.currentTimeMillis() - roundStart);

            advanceToNextRound(tournamentId);
            roundsPlayed++;
        }

        int remaining = registrationRepository.countActiveByTournamentId(tournamentId);
        if (remaining == 1) {
            endTournamentInTransaction(tournamentId);
        } else if (remaining > 1) {
            throw new IllegalStateException(
                    "Pyramid tournament ended with " + remaining + " players still active");
        }

        Tournament finished = loadTournament(tournamentId);
        UUID championId = registrationRepository.findByTournamentIdOrderByPosition(tournamentId).stream()
                .filter(r -> r.getFinishPosition() != null && r.getFinishPosition() == 1)
                .findFirst()
                .map(TournamentRegistration::getPlayerId)
                .orElseThrow(() -> new IllegalStateException("No champion found"));

        log.info("Pyramid tournament {} completed in {} ms, rounds={}, champion={}",
                tournamentId, System.currentTimeMillis() - runStart, roundsPlayed, championId);

        return new PyramidRunResult(tournamentId, championId, roundsPlayed, finished.getStatus());
    }

    private void processRoundTables(UUID tournamentId, List<TournamentTable> tables, int handsPerRound) {
        int parallelism = Math.min(tournamentProperties.getPyramidTableParallelism(), tables.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<?>> futures = new ArrayList<>(tables.size());
            for (TournamentTable table : tables) {
                UUID tableId = table.getId();
                futures.add(executor.submit(() -> transactionTemplate.execute(status -> {
                    playHandsOnTable(tournamentId, tableId, handsPerRound);
                    resolveTableSurvivor(tournamentId, tableId);
                    return null;
                })));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Parallel pyramid table processing failed", e);
        } finally {
            executor.shutdown();
        }
    }

    @Transactional
    public void playHandsOnTable(UUID tournamentId, UUID tableId, int handCount) {
        for (int h = 0; h < handCount; h++) {
            playOneHandToCompletion(tournamentId, tableId);
        }
    }

    private void playOneHandToCompletion(UUID tournamentId, UUID tableId) {
        Game game = tableGameService.getOrStartTableHand(tournamentId, tableId);
        UUID gameId = game.getId();
        int actions = 0;

        while (!game.isFinished() && actions < MAX_ACTIONS_PER_HAND) {
            game = pokerGameService.getGame(gameId).orElseThrow(
                    () -> new ResourceNotFoundException("Game not found: " + gameId));
            Player current = game.getCurrentPlayer();
            if (current == null || current.isFolded() || current.isAllIn()) {
                actions++;
                continue;
            }
            if (!current.isBot()) {
                throw new IllegalStateException(
                        "Pyramid simulation requires bot players; found human: " + current.getName());
            }
            game = pokerGameService.executeBotAction(gameId, current.getId());
            actions++;
        }

        if (!game.isFinished()) {
            throw new IllegalStateException(
                    "Hand did not finish on table " + tableId + " after " + MAX_ACTIONS_PER_HAND + " actions");
        }
    }

    /**
     * Keeps chip leader at the table; eliminates all other seated players (no MTT rebalance).
     */
    @Transactional
    public UUID resolveTableSurvivor(UUID tournamentId, UUID tableId) {
        TournamentTable table = tableRepository.findByIdAndTournamentIdWithDetails(tableId, tournamentId)
                .orElseThrow(() -> new ResourceNotFoundException("Table not found: " + tableId));
        Tournament tournament = table.getTournament();

        List<TournamentRegistration> seated = new ArrayList<>();
        for (UUID playerId : table.getPlayerIds()) {
            registrationRepository.findByTournamentIdAndPlayerId(tournamentId, playerId)
                    .filter(TournamentRegistration::isActive)
                    .ifPresent(seated::add);
        }

        if (seated.isEmpty()) {
            table.close();
            tableRepository.save(table);
            return null;
        }

        if (seated.size() == 1) {
            table.clearCurrentGame();
            return seated.get(0).getPlayerId();
        }

        TournamentRegistration winner = seated.stream()
                .max(Comparator.comparingInt(TournamentRegistration::getCurrentChips)
                        .thenComparing(r -> r.getPlayerId().toString()))
                .orElseThrow();

        UUID winnerId = winner.getPlayerId();
        for (TournamentRegistration reg : seated) {
            if (!reg.getPlayerId().equals(winnerId)) {
                pyramidEliminate(tournament, reg);
            }
        }

        table.clearCurrentGame();
        table.close();
        tableRepository.save(table);

        log.debug("Table {} resolved: survivor {} ({} chips)",
                table.getTableNumber(), winner.getPlayerName(), winner.getCurrentChips());
        return winnerId;
    }

    /**
     * Closes old tables and seats all survivors for the next pyramid level.
     */
    @Transactional
    public void advanceToNextRound(UUID tournamentId) {
        Tournament tournament = loadTournament(tournamentId);
        int activeCount = registrationRepository.countActiveByTournamentId(tournamentId);

        if (activeCount <= 1) {
            return;
        }

        List<TournamentTable> oldTables = tableRepository.findActiveTablesByTournament(tournamentId);
        for (TournamentTable old : oldTables) {
            old.close();
        }
        tableRepository.saveAll(oldTables);

        List<UUID> survivorIds = registrationRepository.findPlayerIdsForSeating(tournamentId);
        int tableCount = (int) Math.ceil((double) activeCount / tournament.getSeatsPerTable());

        List<TournamentTable> newTables = new ArrayList<>(tableCount);
        boolean finalTable = activeCount <= tournament.getSeatsPerTable();
        for (int n = 1; n <= tableCount; n++) {
            TournamentTable table = finalTable && n == 1
                    ? TournamentTable.createFinalTable(tournament)
                    : new TournamentTable(tournament, n);
            newTables.add(table);
        }

        int tableIndex = 0;
        for (UUID playerId : survivorIds) {
            TournamentTable table = newTables.get(tableIndex);
            while (table.isFull() && tableIndex < newTables.size() - 1) {
                tableIndex++;
                table = newTables.get(tableIndex);
            }
            table.seatPlayer(playerId);
            if (!table.isFull()) {
                tableIndex = (tableIndex + 1) % newTables.size();
            }
        }

        tableRepository.saveAll(newTables);
        tournament.incrementPyramidRound();
        tournamentRepository.save(tournament);

        log.info("Advanced pyramid tournament {} to round {} with {} tables and {} survivors",
                tournamentId, tournament.getPyramidRound(), tableCount, activeCount);
    }

    @Transactional
    protected void endTournamentInTransaction(UUID tournamentId) {
        tournamentService.endTournament(tournamentId);
    }

    private void pyramidEliminate(Tournament tournament, TournamentRegistration eliminated) {
        if (!eliminated.isActive()) {
            return;
        }
        UUID tournamentId = tournament.getId();
        UUID playerId = eliminated.getPlayerId();
        String playerName = eliminated.getPlayerName();
        int finishPosition = registrationRepository.countActiveByTournamentId(tournamentId);
        int prize = tournament.calculatePrizeForPosition(finishPosition);

        eliminated.eliminate(finishPosition, prize);
        registrationRepository.save(eliminated);

        int playersRemaining = registrationRepository.countActiveByTournamentId(tournamentId);
        eventPublisher.publishEvent(new TournamentPlayerEliminated(
                tournamentId,
                playerId,
                playerName,
                finishPosition,
                prize,
                playersRemaining,
                null));
    }

    private Tournament loadTournament(UUID tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + tournamentId));
    }

    private void assertPyramid(Tournament tournament) {
        if (tournament.getTournamentType() != TournamentType.PYRAMID) {
            throw new IllegalArgumentException("Tournament is not PYRAMID type: " + tournament.getTournamentType());
        }
    }

    public record PyramidRunResult(
            UUID tournamentId,
            UUID championId,
            int roundsPlayed,
            TournamentStatus finalStatus) {
    }
}
