package com.truholdem.service;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.*;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.dto.TournamentDetailResponse;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.*;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


@Service
@Transactional
public class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);
    private static final int MAX_PLAYERS_PER_TABLE = 9;
    private static final int IDEAL_PLAYERS_PER_TABLE = 8;
    private static final int MIN_PLAYERS_TO_PLAY = 2;

    private final TournamentRepository tournamentRepository;
    private final TournamentRegistrationRepository registrationRepository;
    private final TournamentTableRepository tableRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TournamentStartService tournamentStartService;
    private final AppProperties.Tournament tournamentProperties;

    public TournamentService(TournamentRepository tournamentRepository,
                             TournamentRegistrationRepository registrationRepository,
                             TournamentTableRepository tableRepository,
                             ApplicationEventPublisher eventPublisher,
                             TournamentStartService tournamentStartService,
                             AppProperties appProperties) {
        this.tournamentRepository = tournamentRepository;
        this.registrationRepository = registrationRepository;
        this.tableRepository = tableRepository;
        this.eventPublisher = eventPublisher;
        this.tournamentStartService = tournamentStartService;
        this.tournamentProperties = appProperties.getTournament();
    }

    
    
    

    
    public Tournament createTournament(CreateTournamentRequest request) {
        log.info("Creating tournament: {} (type: {})", request.name(), request.type());
        
        validateCreateRequest(request);
        
        Tournament tournament = buildTournament(request);
        tournament = tournamentRepository.save(tournament);
        
        publishEvent(new TournamentCreated(
            tournament.getId(),
            tournament.getName(),
            tournament.getTournamentType(),
            tournament.getBuyIn(),
            tournament.getStartingChips(),
            tournament.getMaxPlayers()
        ));
        
        log.info("Tournament created: {} (id: {})", tournament.getName(), tournament.getId());
        return tournament;
    }

    
    public TournamentRegistration registerPlayer(UUID tournamentId, UUID playerId, String playerName) {
        log.info("Registering player {} for tournament {}", playerName, tournamentId);

        Tournament tournament = findTournamentOrThrow(tournamentId);
        int registeredBefore = registrationRepository.countByTournamentId(tournamentId);

        validateCanRegister(tournament, playerId, registeredBefore);

        TournamentRegistration registration = new TournamentRegistration(tournament, playerId, playerName);
        registration = registrationRepository.save(registration);

        int currentCount = registeredBefore + 1;

        publishEvent(new TournamentPlayerRegistered(
                tournamentId,
                playerId,
                playerName,
                currentCount,
                tournament.getMaxPlayers()));

        if (shouldAutoStart(tournament, currentCount)) {
            log.info("Tournament {} is full, auto-starting", tournamentId);
            startTournament(tournamentId);
        }

        return registration;
    }

    
    public void unregisterPlayer(UUID tournamentId, UUID playerId) {
        log.info("Unregistering player {} from tournament {}", playerId, tournamentId);

        Tournament tournament = findTournamentOrThrow(tournamentId);
        if (tournament.getStatus() != TournamentStatus.REGISTERING) {
            throw new IllegalStateException("Can only unregister during REGISTERING status");
        }
        if (!registrationRepository.existsByTournamentIdAndPlayerId(tournamentId, playerId)) {
            throw new IllegalStateException("Player not found in tournament");
        }

        registrationRepository.deleteByTournamentIdAndPlayerId(tournamentId, playerId);
    }

    
    public void startTournament(UUID tournamentId) {
        log.info("Starting tournament {}", tournamentId);

        Tournament tournament = findTournamentOrThrow(tournamentId);
        int registeredCount = registrationRepository.countByTournamentId(tournamentId);
        validateCanStart(tournament, registeredCount);

        if (tournamentStartService.shouldStartAsynchronously(registeredCount)) {
            tournamentStartService.requestAsyncStart(tournamentId);
        } else {
            tournamentStartService.completeStart(tournamentId);
        }
    }

    
    public void pauseTournament(UUID tournamentId) {
        log.info("Pausing tournament {}", tournamentId);
        
        Tournament tournament = findTournamentOrThrow(tournamentId);
        
        if (!tournament.getStatus().isPlayable()) {
            throw new IllegalStateException("Tournament is not in a playable state");
        }
        
        tournamentStartService.cancelScheduledLevelIncrease(tournamentId);
        tournamentRepository.save(tournament);
    }

    
    
    

    
    List<TournamentTable> createInitialTables(Tournament tournament) {
        int playerCount = tournament.getRegistrations().size();
        int tableCount = calculateTableCount(playerCount);
        
        log.debug("Creating {} tables for {} players", tableCount, playerCount);
        
        List<TournamentTable> tables = new ArrayList<>();
        for (int i = 1; i <= tableCount; i++) {
            TournamentTable table = new TournamentTable(tournament, i);
            tables.add(table);
        }
        
        return tableRepository.saveAll(tables);
    }

    
    void seatPlayersRandomly(Tournament tournament) {
        List<TournamentRegistration> registrations = new ArrayList<>(tournament.getActiveRegistrations());
        Collections.shuffle(registrations);
        
        List<TournamentTable> tables = tournament.getActiveTables();
        if (tables.isEmpty()) {
            throw new IllegalStateException("No active tables to seat players");
        }
        
        int tableIndex = 0;
        for (TournamentRegistration reg : registrations) {
            TournamentTable table = tables.get(tableIndex);
            
            
            while (table.isFull() && tableIndex < tables.size() - 1) {
                tableIndex++;
                table = tables.get(tableIndex);
            }
            
            table.seatPlayer(reg.getPlayerId());
            tableIndex = (tableIndex + 1) % tables.size();
        }
        
        tableRepository.saveAll(tables);
        log.debug("Seated {} players across {} tables", registrations.size(), tables.size());
    }

    
    public void rebalanceTables(UUID tournamentId) {
        log.debug("Rebalancing tables for tournament {}", tournamentId);
        
        Tournament tournament = findTournamentOrThrow(tournamentId);
        List<TournamentTable> activeTables = tableRepository.findActiveTablesByTournament(tournamentId);
        
        if (activeTables.size() <= 1) {
            return; 
        }
        
        List<TournamentTablesRebalanced.PlayerMove> moves = new ArrayList<>();
        List<UUID> closedTableIds = new ArrayList<>();
        
        
        activeTables.sort(Comparator.comparingInt(TournamentTable::getPlayerCount));
        
        boolean rebalanced = true;
        while (rebalanced) {
            rebalanced = false;
            
            TournamentTable smallest = activeTables.get(0);
            TournamentTable largest = activeTables.get(activeTables.size() - 1);
            
            
            if (largest.getPlayerCount() - smallest.getPlayerCount() > 1) {
                UUID playerToMove = largest.getPlayerIds().get(largest.getPlayerCount() - 1);
                largest.removePlayer(playerToMove);
                smallest.seatPlayer(playerToMove);
                
                moves.add(new TournamentTablesRebalanced.PlayerMove(
                    playerToMove, largest.getId(), smallest.getId()));
                
                rebalanced = true;
                activeTables.sort(Comparator.comparingInt(TournamentTable::getPlayerCount));
            }
        }
        
        
        for (TournamentTable table : activeTables) {
            if (table.getPlayerCount() == 0) {
                table.close();
                closedTableIds.add(table.getId());
            }
        }
        
        
        int totalPlayers = activeTables.stream()
                                       .filter(TournamentTable::isActive)
                                       .mapToInt(TournamentTable::getPlayerCount)
                                       .sum();
        
        boolean finalTableFormed = false;
        if (totalPlayers <= MAX_PLAYERS_PER_TABLE && activeTables.stream()
                .filter(TournamentTable::isActive).count() > 1) {
            finalTableFormed = consolidateToFinalTable(tournament, activeTables, moves, closedTableIds);
        }
        
        tableRepository.saveAll(activeTables);
        tournamentRepository.save(tournament);
        
        if (!moves.isEmpty() || !closedTableIds.isEmpty()) {
            int activeCount = (int) activeTables.stream().filter(TournamentTable::isActive).count();
            publishEvent(new TournamentTablesRebalanced(
                tournamentId, activeCount, moves, closedTableIds, finalTableFormed));
        }
    }

    
    private boolean consolidateToFinalTable(Tournament tournament, 
                                            List<TournamentTable> tables,
                                            List<TournamentTablesRebalanced.PlayerMove> moves,
                                            List<UUID> closedTableIds) {
        log.info("Consolidating to final table for tournament {}", tournament.getId());
        
        
        TournamentTable finalTable = tables.stream()
            .filter(TournamentTable::isActive)
            .max(Comparator.comparingInt(TournamentTable::getPlayerCount))
            .orElseThrow();
        
        
        for (TournamentTable table : tables) {
            if (table.isActive() && !table.equals(finalTable)) {
                for (UUID playerId : new ArrayList<>(table.getPlayerIds())) {
                    table.removePlayer(playerId);
                    finalTable.seatPlayer(playerId);
                    moves.add(new TournamentTablesRebalanced.PlayerMove(
                        playerId, table.getId(), finalTable.getId()));
                }
                table.close();
                closedTableIds.add(table.getId());
            }
        }
        
        
        return true;
    }

    
    
    

    
    @Transactional
    public void advanceLevel(UUID tournamentId) {
        log.info("Advancing blind level for tournament {}", tournamentId);
        tournamentStartService.advanceLevel(tournamentId);
    }

    
    
    

    
    public void handlePlayerElimination(UUID tournamentId, UUID playerId) {
        handlePlayerElimination(tournamentId, playerId, null);
    }

    
    public void handlePlayerElimination(UUID tournamentId, UUID playerId, UUID eliminatedBy) {
        log.info("Handling elimination of player {} from tournament {}", playerId, tournamentId);
        
        Tournament tournament = findTournamentOrThrow(tournamentId);
        
        TournamentRegistration eliminated = tournament.findRegistration(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found in tournament"));
        
        if (!eliminated.isActive()) {
            throw new IllegalStateException("Player already eliminated");
        }
        
        String playerName = eliminated.getPlayerName();
        int finishPosition = tournament.getPlayersRemaining();
        int prize = tournament.calculatePrizeForPosition(finishPosition);
        
        
        if (eliminatedBy != null && tournament.getTournamentType() == TournamentType.BOUNTY) {
            tournament.recordBounty(eliminatedBy, playerId);
        }
        
        
        tournament.eliminatePlayer(playerId);
        
        
        tournamentRepository.save(tournament);
        
        int playersRemaining = tournament.getPlayersRemaining();
        
        publishEvent(new TournamentPlayerEliminated(
            tournamentId,
            playerId,
            playerName,
            finishPosition,
            prize,
            playersRemaining,
            eliminatedBy
        ));
        
        log.info("Player {} eliminated from tournament {} in position {} (prize: {})", 
                 playerName, tournamentId, finishPosition, prize);
        
        
        if (playersRemaining <= 1) {
            endTournament(tournamentId);
        } else {
            rebalanceTables(tournamentId);
        }
    }

    
    
    

    
    public void endTournament(UUID tournamentId) {
        log.info("Ending tournament {}", tournamentId);
        
        Tournament tournament = findTournamentOrThrow(tournamentId);
        
        
        tournamentStartService.cancelScheduledLevelIncrease(tournamentId);

        // Check if winner is already determined (tournament auto-completed when last player eliminated)
        TournamentRegistration winner = tournament.getRegistrations().stream()
            .filter(r -> r.getFinishPosition() != null && r.getFinishPosition() == 1)
            .findFirst()
            .orElseGet(() -> {
                // No winner yet - find the last active player
                TournamentRegistration activeWinner = tournament.getActiveRegistrations().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No winner found"));
                
                int firstPrize = tournament.calculatePrizeForPosition(1);
                activeWinner.finish(1, firstPrize);
                return activeWinner;
            });
        
        tournamentRepository.save(tournament);
        
        
        List<TournamentCompleted.FinishResult> topFinishers = tournament.getRegistrations().stream()
            .filter(r -> r.getFinishPosition() != null)
            .sorted(Comparator.comparingInt(TournamentRegistration::getFinishPosition))
            .limit(10)
            .map(r -> new TournamentCompleted.FinishResult(
                r.getFinishPosition(),
                r.getPlayerId(),
                r.getPlayerName(),
                r.getPrizeWon()
            ))
            .collect(Collectors.toList());
        
        Duration duration = tournament.getStartTime() != null 
            ? Duration.between(tournament.getStartTime(), Instant.now())
            : Duration.ZERO;
        
        publishEvent(new TournamentCompleted(
            tournamentId,
            winner.getPlayerId(),
            winner.getPlayerName(),
            tournament.getPrizePool(),
            tournament.getRegistrations().size(),
            tournament.getCurrentLevel(),
            duration,
            topFinishers
        ));
        
        log.info("Tournament {} completed. Winner: {} (prize: {})", 
                 tournamentId, winner.getPlayerName(), winner.getPrizeWon());
    }

    
    
    

    
    @Transactional(readOnly = true)
    public Tournament getTournament(UUID tournamentId) {
        return findTournamentOrThrow(tournamentId);
    }

    @Transactional(readOnly = true)
    public TournamentDetailResponse getTournamentDetail(UUID tournamentId) {
        Tournament tournament = findTournamentOrThrow(tournamentId);
        int registered = registrationRepository.countByTournamentId(tournamentId);
        int remaining = registrationRepository.countActiveByTournamentId(tournamentId);
        int tableCount = tableRepository.countActiveTablesByTournament(tournamentId);

        String chipLeaderName = null;
        int chipLeaderStack = 0;
        Page<TournamentRegistration> leaders = registrationRepository.findByTournamentIdOrderByChipsDesc(
                tournamentId, PageRequest.of(0, 1));
        if (leaders.hasContent()) {
            TournamentRegistration leader = leaders.getContent().get(0);
            chipLeaderName = leader.getPlayerName();
            chipLeaderStack = leader.getCurrentChips();
        }

        int averageStack = 0;
        if (remaining > 0) {
            averageStack = (int) (registrationRepository.sumActiveChipsByTournamentId(tournamentId) / remaining);
        }

        int prizePool = tournament.getBuyIn() * registered;
        List<TournamentDetailResponse.TableSummary> tables = List.of();
        if (tableCount > 0 && tableCount <= 100) {
            tables = tableRepository.findActiveTablesByTournament(tournamentId).stream()
                    .map(TournamentDetailResponse.TableSummary::from)
                    .toList();
        }

        return TournamentDetailResponse.fromSummary(
                tournament,
                registered,
                remaining,
                tableCount,
                tables,
                chipLeaderName,
                chipLeaderStack,
                averageStack,
                prizePool);
    }

    
    @Transactional(readOnly = true)
    public List<Tournament> getOpenTournaments() {
        return tournamentRepository.findOpenTournaments();
    }

    
    @Transactional(readOnly = true)
    public List<Tournament> getRunningTournaments() {
        return tournamentRepository.findRunningTournaments();
    }

    
    @Transactional(readOnly = true)
    public List<Tournament> getPlayerTournaments(UUID playerId) {
        return tournamentRepository.findByPlayerId(playerId);
    }

    
    @Transactional(readOnly = true)
    public List<TournamentRegistration> getLeaderboard(UUID tournamentId) {
        return registrationRepository.findByTournamentIdOrderByChipsDesc(tournamentId);
    }

    @Transactional(readOnly = true)
    public Page<TournamentRegistration> getLeaderboard(UUID tournamentId, Pageable pageable) {
        findTournamentOrThrow(tournamentId);
        return registrationRepository.findByTournamentIdOrderByChipsDesc(tournamentId, pageable);
    }

    
    public TournamentRegistration processRebuy(UUID tournamentId, UUID playerId) {
        log.info("Processing rebuy for player {} in tournament {}", playerId, tournamentId);
        
        Tournament tournament = findTournamentOrThrow(tournamentId);
        
        if (!tournament.getStatus().isPlayable()) {
            throw new IllegalStateException("Tournament is not in a playable state");
        }
        
        TournamentRegistration registration = tournament.findRegistration(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found in tournament"));
        
        if (!registration.canRebuy()) {
            throw new IllegalStateException("Player cannot rebuy: either past deadline level or max rebuys reached");
        }
        
        registration.rebuy(tournament.getRebuyAmount());
        tournamentRepository.save(tournament);
        
        log.info("Player {} completed rebuy #{} in tournament {}", 
                 playerId, registration.getRebuysUsed(), tournamentId);
        
        return registration;
    }

    
    @Transactional(readOnly = true)
    public List<TournamentTable> getTournamentTables(UUID tournamentId) {
        findTournamentOrThrow(tournamentId); 
        return tableRepository.findActiveTablesByTournament(tournamentId);
    }

    
    @Transactional(readOnly = true)
    public TournamentTable getTournamentTable(UUID tournamentId, UUID tableId) {
        findTournamentOrThrow(tournamentId);
        return tableRepository.findById(tableId)
            .filter(t -> t.getTournament().getId().equals(tournamentId))
            .orElseThrow(() -> new ResourceNotFoundException("Table not found: " + tableId));
    }

    @Transactional(readOnly = true)
    public com.truholdem.dto.TableDetailResponse getTableDetail(UUID tournamentId, UUID tableId) {
        findTournamentOrThrow(tournamentId);
        TournamentTable table = tableRepository.findByIdAndTournamentIdWithDetails(tableId, tournamentId)
                .orElseThrow(() -> new ResourceNotFoundException("Table not found: " + tableId));

        List<com.truholdem.dto.TableDetailResponse.TablePlayerInfo> players =
                table.getPlayerIds().stream()
                        .map(playerId -> registrationRepository.findByTournamentIdAndPlayerId(tournamentId, playerId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Registration not found: " + playerId)))
                        .map(reg -> new com.truholdem.dto.TableDetailResponse.TablePlayerInfo(
                                reg.getPlayerId(),
                                reg.getPlayerName(),
                                reg.getCurrentChips(),
                                reg.getPlayerName() != null
                                        && reg.getPlayerName().regionMatches(true, 0, "bot", 0, 3)))
                        .toList();

        UUID currentGameId = table.getCurrentGame() != null ? table.getCurrentGame().getId() : null;
        return com.truholdem.dto.TableDetailResponse.from(table, players, currentGameId);
    }

    
    @Transactional(readOnly = true)
    public List<Tournament> getTournamentsByStatus(String status) {
        if (status == null || status.equalsIgnoreCase("all")) {
            return tournamentRepository.findTop20ByOrderByCreatedAtDesc();
        }
        
        return switch (status.toUpperCase()) {
            case "OPEN", "REGISTERING" -> tournamentRepository.findOpenTournaments();
            case "RUNNING", "ACTIVE" -> tournamentRepository.findRunningTournaments();
            case "PAUSED" -> tournamentRepository.findPausedTournaments();
            case "COMPLETED" -> tournamentRepository.findRecentlyCompleted();
            default -> tournamentRepository.findTop20ByOrderByCreatedAtDesc();
        };
    }

    
    
    

    private Tournament findTournamentOrThrow(UUID tournamentId) {
        return tournamentRepository.findById(tournamentId)
            .orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + tournamentId));
    }

    private void validateCreateRequest(CreateTournamentRequest request) {
        if (request.minPlayers() > request.maxPlayers()) {
            throw new IllegalArgumentException("Min players cannot exceed max players");
        }
        if (request.maxPlayers() > tournamentProperties.getMaxPlayersLimit()) {
            throw new IllegalArgumentException(
                    "Maximum players cannot exceed " + tournamentProperties.getMaxPlayersLimit());
        }
    }

    private void validateCanRegister(Tournament tournament, UUID playerId, int registeredCount) {
        if (!tournament.getStatus().allowsRegistration()) {
            throw new IllegalStateException("Tournament is not accepting registrations");
        }
        if (registeredCount >= tournament.getMaxPlayers()) {
            throw new IllegalStateException("Tournament is full");
        }
        if (registrationRepository.existsByTournamentIdAndPlayerId(tournament.getId(), playerId)) {
            throw new IllegalStateException("Player already registered");
        }
    }

    private void validateCanStart(Tournament tournament, int registeredCount) {
        if (tournament.getStatus() != TournamentStatus.REGISTERING) {
            throw new IllegalStateException("Tournament cannot start: wrong status " + tournament.getStatus());
        }
        if (registeredCount < tournament.getMinPlayers()) {
            throw new IllegalStateException(
                    "Tournament cannot start: insufficient players (" + registeredCount + ")");
        }
    }

    private boolean shouldAutoStart(Tournament tournament, int registeredCount) {
        return tournament.getTournamentType() == TournamentType.SIT_AND_GO
                && registeredCount >= tournament.getMaxPlayers();
    }

    private Tournament buildTournament(CreateTournamentRequest request) {
        Tournament.TournamentBuilder builder = Tournament.builder(request.name())
            .type(request.type())
            .startingChips(request.startingChips())
            .players(request.minPlayers(), request.maxPlayers())
            .buyIn(request.buyIn());
        
        
        if (request.blindStructureType() != null) {
            BlindStructure structure = switch (request.blindStructureType().toUpperCase()) {
                case "TURBO" -> BlindStructure.turbo();
                case "DEEP" -> BlindStructure.deep();
                default -> BlindStructure.standard();
            };
            builder.blindStructure(structure);
        }
        
        
        if (request.rebuyAmount() != null && request.rebuyAmount() > 0) {
            builder.rebuy(
                request.rebuyAmount(),
                request.rebuyDeadlineLevel() != null ? request.rebuyDeadlineLevel() : 6,
                request.maxRebuys() != null ? request.maxRebuys() : 3
            );
        }
        
        
        if (request.addOnAmount() != null && request.addOnAmount() > 0) {
            builder.addOn(request.addOnAmount());
        }
        
        
        if (request.bountyAmount() != null && request.bountyAmount() > 0) {
            builder.bounty(request.bountyAmount());
        }
        
        
        if (request.payoutStructure() != null && !request.payoutStructure().isEmpty()) {
            builder.payoutStructure(request.payoutStructure());
        }
        
        return builder.build();
    }

    private int calculateTableCount(int playerCount) {
        if (playerCount <= MAX_PLAYERS_PER_TABLE) {
            return 1;
        }
        
        return (int) Math.ceil((double) playerCount / IDEAL_PLAYERS_PER_TABLE);
    }

    private void publishEvent(TournamentEvent event) {
        log.debug("Publishing tournament event: {}", event.getEventType());
        eventPublisher.publishEvent(event);
    }
}
