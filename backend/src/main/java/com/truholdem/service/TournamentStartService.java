package com.truholdem.service;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.*;
import com.truholdem.service.cluster.TableOwnershipService;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentStatus;
import com.truholdem.model.TournamentTable;
import com.truholdem.model.TournamentType;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.tournament.TournamentTimingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class TournamentStartService {

    private static final Logger log = LoggerFactory.getLogger(TournamentStartService.class);
    private static final int MAX_PLAYERS_PER_TABLE = 9;
    private static final int IDEAL_PLAYERS_PER_TABLE = 8;

    private final TournamentRepository tournamentRepository;
    private final TournamentRegistrationRepository registrationRepository;
    private final TournamentTableRepository tableRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskScheduler taskScheduler;
    private final AppProperties.Tournament tournamentProperties;
    private final TournamentTimingService timingService;

    private final TableOwnershipService ownership;
    private final com.truholdem.repository.PyramidBuyoutRepository buyoutRepository;
    private final Map<UUID, ScheduledFuture<?>> scheduledLevelIncreases = new ConcurrentHashMap<>();

    public TournamentStartService(
            TournamentRepository tournamentRepository,
            TournamentRegistrationRepository registrationRepository,
            TournamentTableRepository tableRepository,
            ApplicationEventPublisher eventPublisher,
            TaskScheduler taskScheduler,
            AppProperties appProperties,
            TournamentTimingService timingService,
            TableOwnershipService ownership,
            com.truholdem.repository.PyramidBuyoutRepository buyoutRepository) {
        this.tournamentRepository = tournamentRepository;
        this.registrationRepository = registrationRepository;
        this.tableRepository = tableRepository;
        this.eventPublisher = eventPublisher;
        this.taskScheduler = taskScheduler;
        this.tournamentProperties = appProperties.getTournament();
        this.timingService = timingService;
        this.ownership = ownership;
        this.buyoutRepository = buyoutRepository;
    }

    public boolean shouldStartAsynchronously(int registeredPlayerCount) {
        return registeredPlayerCount >= tournamentProperties.getAsyncStartThreshold();
    }

    @Transactional
    public void markStarting(UUID tournamentId) {
        int updated = tournamentRepository.updateStatus(tournamentId, TournamentStatus.STARTING);
        if (updated == 0) {
            throw new ResourceNotFoundException("Tournament not found: " + tournamentId);
        }
    }

    public void requestAsyncStart(UUID tournamentId) {
        markStarting(tournamentId);
        startTournamentAsync(tournamentId);
    }

    @Async("tournamentTaskExecutor")
    public void startTournamentAsync(UUID tournamentId) {
        try {
            completeStart(tournamentId);
        } catch (Exception e) {
            log.error("Async tournament start failed for {}", tournamentId, e);
            try {
                tournamentRepository.updateStatus(tournamentId, TournamentStatus.REGISTERING);
            } catch (Exception rollbackEx) {
                log.error("Failed to roll back tournament {} to REGISTERING", tournamentId, rollbackEx);
            }
        }
    }

    @Transactional
    public TournamentStartResult completeStart(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + tournamentId));

        if (tournament.getStatus() != TournamentStatus.REGISTERING
                && tournament.getStatus() != TournamentStatus.STARTING) {
            throw new IllegalStateException(
                    "Tournament cannot start from status: " + tournament.getStatus());
        }

        int playerCount = registrationRepository.countByTournamentId(tournamentId);
        if (playerCount < tournament.getMinPlayers()) {
            throw new IllegalStateException(
                    String.format("Not enough players: %d registered, %d required",
                            playerCount, tournament.getMinPlayers()));
        }

        log.info("Starting tournament {} with {} players (batch size {})",
                tournamentId, playerCount, tournamentProperties.getStartBatchSize());

        registrationRepository.markAllAsPlaying(tournamentId, tournament.getStartingChips());

        tournament.markRunningAtStart();
        tournament = tournamentRepository.save(tournament);

        if (tournament.isPyramidBuyUpEnabled()) {
            // Fixed-bracket seating: floor (non-buyers) on level 1 skipping closed sub-trees; buyers wait for
            // their level (seated when their round arrives — engine advancement is a later slice).
            List<TournamentTable> tables = seatBuyUpPyramidStart(tournament);
            persistTablesInBatches(tables);
            publishStartEvents(tournamentId, tournament, playerCount, tables);
            log.info("Buy-up pyramid {} started: {} floor table(s), {} buy-out(s)",
                    tournamentId, tables.size(), buyoutRepository.countByTournamentId(tournamentId));
            return new TournamentStartResult(tournament, playerCount, tables.size(), tables);
        }

        List<UUID> playerIds = registrationRepository.findPlayerIdsForSeating(tournamentId);
        int tableCount = calculateTableCount(tournament, playerCount);

        List<TournamentTable> tables = new ArrayList<>(tableCount);
        for (int tableNumber = 1; tableNumber <= tableCount; tableNumber++) {
            tables.add(new TournamentTable(tournament, tableNumber));
        }

        seatPlayersForStart(tournament, tables, playerIds);

        persistTablesInBatches(tables);

        if (tournament.getTournamentType() != TournamentType.PYRAMID) {
            scheduleLevelIncrease(tournament);
        }
        publishStartEvents(tournamentId, tournament, playerCount, tables);

        log.info("Tournament {} started with {} players at {} tables",
                tournamentId, playerCount, tableCount);

        return new TournamentStartResult(tournament, playerCount, tableCount, tables);
    }

    private void persistTablesInBatches(List<TournamentTable> tables) {
        int batchSize = tournamentProperties.getStartBatchSize();
        for (int offset = 0; offset < tables.size(); offset += batchSize) {
            int end = Math.min(offset + batchSize, tables.size());
            tableRepository.saveAll(tables.subList(offset, end));
        }
    }

    private void publishStartEvents(
            UUID tournamentId,
            Tournament tournament,
            int playerCount,
            List<TournamentTable> tables) {
        publishEvent(new TournamentStarted(
                tournamentId,
                playerCount,
                tables.size(),
                estimatePrizePool(tournament, playerCount)));

        for (TournamentTable table : tables) {
            publishEvent(new TournamentTableCreated(
                    tournamentId,
                    table.getId(),
                    table.getTableNumber(),
                    table.getPlayerIds(),
                    table.isFinalTable()));
        }
    }

    private int estimatePrizePool(Tournament tournament, int playerCount) {
        return tournament.getBuyIn() * playerCount;
    }

    public void scheduleLevelIncrease(Tournament tournament) {
        UUID tournamentId = tournament.getId();
        // Only the owning node drives this tournament's blind-level progression.
        if (!ownership.acquire(tournamentId)) {
            return;
        }
        Duration levelDuration = timingService.levelDuration(tournament);

        Runnable task = () -> {
            if (!ownership.isOwner(tournamentId)) {
                return; // lease moved to another node
            }
            try {
                advanceLevel(tournamentId);
            } catch (Exception e) {
                log.error("Error advancing level for tournament {}", tournamentId, e);
            }
        };

        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                task,
                Instant.now().plus(levelDuration),
                levelDuration);

        scheduledLevelIncreases.put(tournament.getId(), future);
    }

    @Transactional
    public void advanceLevel(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + tournamentId));

        if (!tournament.getStatus().isPlayable()) {
            cancelScheduledLevelIncrease(tournamentId);
            return;
        }

        tournament.advanceLevel();
        tournamentRepository.save(tournament);

        var newLevel = tournament.getCurrentBlindLevel();
        int playersRemaining = registrationRepository.countActiveByTournamentId(tournamentId);

        publishEvent(new TournamentLevelAdvanced(
                tournamentId,
                tournament.getCurrentLevel(),
                newLevel.getSmallBlind(),
                newLevel.getBigBlind(),
                newLevel.getAnte(),
                playersRemaining));
    }

    void cancelScheduledLevelIncrease(UUID tournamentId) {
        ScheduledFuture<?> future = scheduledLevelIncreases.remove(tournamentId);
        if (future != null) {
            future.cancel(false);
        }
        ownership.release(tournamentId);
    }

    private int calculateTableCount(Tournament tournament, int playerCount) {
        if (tournament.getTournamentType() == TournamentType.PYRAMID) {
            int seats = tournament.getSeatsPerTable();
            return (int) Math.ceil((double) playerCount / seats);
        }
        if (playerCount <= MAX_PLAYERS_PER_TABLE) {
            return 1;
        }
        return (int) Math.ceil((double) playerCount / IDEAL_PLAYERS_PER_TABLE);
    }

    /**
     * Fixed-bracket level-1 seating for a buy-up pyramid. Floor players (registered non-buyers) are placed
     * per {@link com.truholdem.service.tournament.PyramidSeatingPlanner}, skipping the seats inside closed
     * (bought) sub-trees; only the level-1 tables that hold a floor player are created. Buyers stay PLAYING
     * but unseated until their level's round (advancement is wired in a later slice).
     */
    private List<TournamentTable> seatBuyUpPyramidStart(Tournament tournament) {
        UUID tournamentId = tournament.getId();
        com.truholdem.service.tournament.PyramidBracket bracket =
                new com.truholdem.service.tournament.PyramidBracket(
                        tournament.getMaxPlayers(), tournament.getSeatsPerTable());

        List<com.truholdem.model.PyramidBuyout> buyouts = buyoutRepository.findByTournamentId(tournamentId);
        java.util.Set<UUID> buyers = buyouts.stream()
                .map(com.truholdem.model.PyramidBuyout::getBuyerPlayerId)
                .collect(java.util.stream.Collectors.toSet());

        List<UUID> floor = registrationRepository.findPlayerIdsForSeating(tournamentId).stream()
                .filter(id -> !buyers.contains(id))
                .toList();

        List<com.truholdem.service.tournament.PyramidSeatingPlanner.Buyout> plannerBuyouts = buyouts.stream()
                .map(b -> new com.truholdem.service.tournament.PyramidSeatingPlanner.Buyout(
                        b.getBuyerPlayerId(), b.getLevel(), b.getSeatIndex()))
                .toList();

        var plan = com.truholdem.service.tournament.PyramidSeatingPlanner.plan(bracket, floor, plannerBuyouts);

        int seatsPerTable = bracket.seatsPerTable();
        java.util.Map<Integer, TournamentTable> byTableIndex = new java.util.TreeMap<>();
        for (var floorSeat : plan.floor()) {
            int tableIndex = floorSeat.seatIndex() / seatsPerTable;
            TournamentTable table = byTableIndex.computeIfAbsent(tableIndex, idx -> {
                TournamentTable t = new TournamentTable(tournament, idx + 1);
                t.setBracketLevel(1);
                return t;
            });
            table.seatPlayer(floorSeat.playerId());
        }
        return new ArrayList<>(byTableIndex.values());
    }

    private void seatPlayersForStart(Tournament tournament, List<TournamentTable> tables, List<UUID> playerIds) {
        if (tournament.getTournamentType() == TournamentType.PYRAMID) {
            int tableIndex = 0;
            for (UUID playerId : playerIds) {
                TournamentTable table = tables.get(tableIndex);
                while (table.isFull() && tableIndex < tables.size() - 1) {
                    tableIndex++;
                    table = tables.get(tableIndex);
                }
                table.seatPlayer(playerId);
                if (!table.isFull()) {
                    tableIndex = (tableIndex + 1) % tables.size();
                }
            }
            return;
        }
        for (int i = 0; i < playerIds.size(); i++) {
            tables.get(i % tables.size()).seatPlayer(playerIds.get(i));
        }
    }

    private void publishEvent(TournamentEvent event) {
        eventPublisher.publishEvent(event);
    }
}
