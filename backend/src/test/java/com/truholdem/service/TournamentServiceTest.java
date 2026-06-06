package com.truholdem.service;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.*;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.*;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TournamentService Tests")
class TournamentServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;
    
    @Mock
    private TournamentRegistrationRepository registrationRepository;
    
    @Mock
    private TournamentTableRepository tableRepository;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TournamentStartService tournamentStartService;

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppProperties.Tournament tournamentProperties;

    @Mock
    private com.truholdem.service.tournament.TournamentTimingService timingService;

    @Captor
    private ArgumentCaptor<TournamentEvent> eventCaptor;
    
    private TournamentService tournamentService;
    
    @BeforeEach
    void setUp() {
        when(appProperties.getTournament()).thenReturn(tournamentProperties);
        when(tournamentProperties.getMaxPlayersLimit()).thenReturn(10_000);
        when(tournamentProperties.getAsyncStartThreshold()).thenReturn(500);
        when(tournamentProperties.getDefaultPageSize()).thenReturn(50);

        tournamentService = new TournamentService(
            tournamentRepository,
            registrationRepository,
            tableRepository,
            eventPublisher,
            tournamentStartService,
            appProperties,
            timingService
        );
    }
    
    /**
     * Helper method to set Tournament ID via reflection (simulating JPA GeneratedValue)
     */
    private static void setTournamentId(Tournament tournament, UUID id) {
        try {
            java.lang.reflect.Field idField = Tournament.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(tournament, id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set tournament ID", e);
        }
    }

    
    
    
    
    @Nested
    @DisplayName("Tournament Creation")
    class TournamentCreationTests {
        
        @Test
        @DisplayName("should create freezeout tournament")
        void shouldCreateFreezeoutTournament() {
            CreateTournamentRequest request = CreateTournamentRequest.freezeout(
                "Sunday Freezeout", 100, 50);
            
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> {
                    Tournament t = inv.getArgument(0);
                    setTournamentId(t, UUID.randomUUID());
                    return t;
                });
            
            Tournament result = tournamentService.createTournament(request);
            
            assertThat(result.getName()).isEqualTo("Sunday Freezeout");
            assertThat(result.getTournamentType()).isEqualTo(TournamentType.FREEZEOUT);
            assertThat(result.getBuyIn()).isEqualTo(100);
            assertThat(result.getMaxPlayers()).isEqualTo(50);
            
            verify(eventPublisher).publishEvent(any(TournamentCreated.class));
        }
        
        @Test
        @DisplayName("should create rebuy tournament with rebuy settings")
        void shouldCreateRebuyTournament() {
            CreateTournamentRequest request = CreateTournamentRequest.rebuy(
                "Rebuy Madness", 50, 100);
            
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> {
                    Tournament t = inv.getArgument(0);
                    setTournamentId(t, UUID.randomUUID());
                    return t;
                });
            
            Tournament result = tournamentService.createTournament(request);
            
            assertThat(result.getTournamentType()).isEqualTo(TournamentType.REBUY);
            assertThat(result.getRebuyAmount()).isEqualTo(50);
            assertThat(result.getMaxRebuys()).isGreaterThan(0);
        }
        
        @Test
        @DisplayName("should create bounty tournament")
        void shouldCreateBountyTournament() {
            CreateTournamentRequest request = CreateTournamentRequest.bounty(
                "Knockout", 100, 50, 50);
            
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> {
                    Tournament t = inv.getArgument(0);
                    setTournamentId(t, UUID.randomUUID());
                    return t;
                });
            
            Tournament result = tournamentService.createTournament(request);
            
            assertThat(result.getTournamentType()).isEqualTo(TournamentType.BOUNTY);
            assertThat(result.getBountyAmount()).isEqualTo(50);
        }
        
        @Test
        @DisplayName("should create SNG tournament")
        void shouldCreateSitAndGoTournament() {
            CreateTournamentRequest request = CreateTournamentRequest.sitAndGo(
                "Turbo SNG", 10);
            
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> {
                    Tournament t = inv.getArgument(0);
                    setTournamentId(t, UUID.randomUUID());
                    return t;
                });
            
            Tournament result = tournamentService.createTournament(request);
            
            assertThat(result.getTournamentType()).isEqualTo(TournamentType.SIT_AND_GO);
            assertThat(result.getBlindStructure().getLevelDurationMinutes()).isEqualTo(5);
        }
        
        @Test
        @DisplayName("should reject invalid player counts")
        void shouldRejectInvalidPlayerCounts() {
            CreateTournamentRequest request = new CreateTournamentRequest(
                "Invalid", TournamentType.FREEZEOUT, 1500, 
                10, 5, 
                100, null, null, null, null, null, null, null, null, false);
            
            assertThatThrownBy(() -> tournamentService.createTournament(request))
                .isInstanceOf(IllegalArgumentException.class);
        }
        
        @Test
        @DisplayName("should publish TournamentCreated event")
        void shouldPublishTournamentCreatedEvent() {
            CreateTournamentRequest request = CreateTournamentRequest.freezeout(
                "Test Tournament", 100, 20);
            
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> {
                    Tournament t = inv.getArgument(0);
                    setTournamentId(t, UUID.randomUUID());
                    return t;
                });
            
            tournamentService.createTournament(request);
            
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            TournamentEvent event = eventCaptor.getValue();
            
            assertThat(event).isInstanceOf(TournamentCreated.class);
            TournamentCreated created = (TournamentCreated) event;
            assertThat(created.getTournamentName()).isEqualTo("Test Tournament");
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Player Registration")
    class PlayerRegistrationTests {
        
        private Tournament tournament;
        private UUID tournamentId;
        
        @BeforeEach
        void setUp() {
            tournamentId = UUID.randomUUID();
            tournament = Tournament.builder("Test Tournament")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            setTournamentId(tournament, tournamentId);

            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(registrationRepository.countByTournamentId(tournamentId)).thenReturn(0);
            lenient().when(registrationRepository.existsByTournamentIdAndPlayerId(eq(tournamentId), any()))
                .thenReturn(false);
            when(registrationRepository.save(any(TournamentRegistration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        }
        
        @Test
        @DisplayName("should register player successfully")
        void shouldRegisterPlayer() {
            UUID playerId = UUID.randomUUID();
            
            TournamentRegistration result = tournamentService.registerPlayer(
                tournamentId, playerId, "Hero");
            
            assertThat(result.getPlayerId()).isEqualTo(playerId);
            assertThat(result.getPlayerName()).isEqualTo("Hero");
            verify(registrationRepository).save(any(TournamentRegistration.class));
        }
        
        @Test
        @DisplayName("should publish TournamentPlayerRegistered event")
        void shouldPublishRegistrationEvent() {
            UUID playerId = UUID.randomUUID();
            
            tournamentService.registerPlayer(tournamentId, playerId, "Hero");
            
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isInstanceOf(TournamentPlayerRegistered.class);
        }
        
        @Test
        @DisplayName("should reject duplicate registration")
        void shouldRejectDuplicateRegistration() {
            UUID playerId = UUID.randomUUID();
            when(registrationRepository.existsByTournamentIdAndPlayerId(tournamentId, playerId))
                .thenReturn(true);

            assertThatThrownBy(() -> 
                tournamentService.registerPlayer(tournamentId, playerId, "Hero2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
        }
        
        @Test
        @DisplayName("should reject registration when tournament is full")
        void shouldRejectWhenFull() {
            when(registrationRepository.countByTournamentId(tournamentId)).thenReturn(9);

            assertThatThrownBy(() -> 
                tournamentService.registerPlayer(tournamentId, UUID.randomUUID(), "Extra"))
                .isInstanceOf(IllegalStateException.class);
        }
        
        @Test
        @DisplayName("should auto-start SNG when full")
        void shouldAutoStartSngWhenFull() {

            UUID sngId = UUID.randomUUID();
            Tournament sng = Tournament.builder("SNG Test")
                .type(TournamentType.SIT_AND_GO)
                .players(2, 3)
                .buyIn(10)
                .build();
            // Set the tournament ID
            setTournamentId(sng, sngId);

            sng.registerPlayer(UUID.randomUUID(), "Player1");
            sng.registerPlayer(UUID.randomUUID(), "Player2");

            when(tournamentRepository.findById(sngId)).thenReturn(Optional.of(sng));
            when(registrationRepository.countByTournamentId(sngId)).thenReturn(2, 3);
            lenient().when(registrationRepository.existsByTournamentIdAndPlayerId(eq(sngId), any()))
                .thenReturn(false);
            when(registrationRepository.save(any(TournamentRegistration.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(tournamentStartService.shouldStartAsynchronously(anyInt())).thenReturn(false);

            tournamentService.registerPlayer(sngId, UUID.randomUUID(), "Player3");

            verify(tournamentStartService).completeStart(sngId);
            verify(eventPublisher).publishEvent(any(TournamentPlayerRegistered.class));
        }
        
        @Test
        @DisplayName("should unregister player before start")
        void shouldUnregisterPlayer() {
            UUID playerId = UUID.randomUUID();
            when(registrationRepository.existsByTournamentIdAndPlayerId(tournamentId, playerId))
                .thenReturn(true);

            tournamentService.unregisterPlayer(tournamentId, playerId);

            verify(registrationRepository).deleteByTournamentIdAndPlayerId(tournamentId, playerId);
        }
        
        @Test
        @DisplayName("should throw when unregistering non-existent player")
        void shouldThrowWhenUnregisteringNonExistentPlayer() {
            assertThatThrownBy(() ->
                tournamentService.unregisterPlayer(tournamentId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should reject self-unregister when admin approval is required")
        void shouldRejectSelfUnregisterWhenApprovalRequired() {
            tournament.setUnregisterRequiresApproval(true);
            UUID playerId = UUID.randomUUID();

            assertThatThrownBy(() ->
                tournamentService.unregisterPlayer(tournamentId, playerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin approval");

            verify(registrationRepository, never())
                .deleteByTournamentIdAndPlayerId(tournamentId, playerId);
        }

        @Test
        @DisplayName("admin can cancel a registration even when approval is required")
        void adminCanCancelRegistrationWhenApprovalRequired() {
            tournament.setUnregisterRequiresApproval(true);
            UUID playerId = UUID.randomUUID();
            when(registrationRepository.existsByTournamentIdAndPlayerId(tournamentId, playerId))
                .thenReturn(true);

            tournamentService.adminCancelPlayerRegistration(tournamentId, playerId);

            verify(registrationRepository).deleteByTournamentIdAndPlayerId(tournamentId, playerId);
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Tournament Start")
    class TournamentStartTests {
        
        private Tournament tournament;
        private UUID tournamentId;
        
        @BeforeEach
        void setUp() {
            tournamentId = UUID.randomUUID();
            tournament = Tournament.builder("Test Tournament")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            // Set the tournament ID to match the tournamentId used in mocks
            setTournamentId(tournament, tournamentId);

            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(registrationRepository.countByTournamentId(tournamentId))
                .thenAnswer(inv -> tournament.getRegistrations().size());
            when(tournamentStartService.shouldStartAsynchronously(anyInt())).thenReturn(false);
            lenient().doAnswer(inv -> {
                tournament.start();
                return new TournamentStartResult(
                        tournament,
                        tournament.getRegistrations().size(),
                        tournament.getTables().size(),
                        tournament.getTables());
            }).when(tournamentStartService).completeStart(tournamentId);
        }

        @Test
        @DisplayName("should start tournament with minimum players")
        void shouldStartWithMinimumPlayers() {
            tournament.registerPlayer(UUID.randomUUID(), "Player1");
            tournament.registerPlayer(UUID.randomUUID(), "Player2");
            
            tournamentService.startTournament(tournamentId);
            
            verify(tournamentStartService).completeStart(tournamentId);
        }
        
        @Test
        @DisplayName("should create tables on start")
        void shouldCreateTablesOnStart() {
            tournament.registerPlayer(UUID.randomUUID(), "Player1");
            tournament.registerPlayer(UUID.randomUUID(), "Player2");
            
            tournamentService.startTournament(tournamentId);
            
            verify(tournamentStartService).completeStart(tournamentId);
        }
        
        @Test
        @DisplayName("should delegate start to tournament start service")
        void shouldDelegateStartToStartService() {
            tournament.registerPlayer(UUID.randomUUID(), "Player1");
            tournament.registerPlayer(UUID.randomUUID(), "Player2");

            tournamentService.startTournament(tournamentId);

            verify(tournamentStartService).completeStart(tournamentId);
        }
        
        @Test
        @DisplayName("should reject start without minimum players")
        void shouldRejectStartWithoutMinimumPlayers() {
            tournament.registerPlayer(UUID.randomUUID(), "Solo");
            
            assertThatThrownBy(() -> tournamentService.startTournament(tournamentId))
                .isInstanceOf(IllegalStateException.class);
        }
        
        @Test
        @DisplayName("should initialize player chips on start")
        void shouldInitializeChipsOnStart() {
            UUID player1 = UUID.randomUUID();
            UUID player2 = UUID.randomUUID();
            tournament.registerPlayer(player1, "Player1");
            tournament.registerPlayer(player2, "Player2");
            
            tournamentService.startTournament(tournamentId);
            
            tournament.getRegistrations().forEach(reg -> 
                assertThat(reg.getCurrentChips()).isEqualTo(tournament.getStartingChips()));
        }
        
        @Test
        @DisplayName("should start when three players registered")
        void shouldStartWithThreePlayers() {
            tournament.registerPlayer(UUID.randomUUID(), "Player1");
            tournament.registerPlayer(UUID.randomUUID(), "Player2");
            tournament.registerPlayer(UUID.randomUUID(), "Player3");

            tournamentService.startTournament(tournamentId);

            verify(tournamentStartService).completeStart(tournamentId);
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Blind Level Progression")
    class BlindLevelTests {
        
        private Tournament tournament;
        private UUID tournamentId;
        
        @BeforeEach
        void setUp() {
            tournamentId = UUID.randomUUID();
            tournament = Tournament.builder("Test Tournament")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            
            
            tournament.registerPlayer(UUID.randomUUID(), "Player1");
            tournament.registerPlayer(UUID.randomUUID(), "Player2");
            tournament.start();
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        }
        
        @Test
        @DisplayName("should delegate blind level advance")
        void shouldDelegateBlindLevelAdvance() {
            tournamentService.advanceLevel(tournamentId);
            verify(tournamentStartService).advanceLevel(tournamentId);
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Table Balancing")
    class TableBalancingTests {
        
        @Test
        @DisplayName("should calculate correct table count for small field")
        void shouldCalculateTableCountForSmallField() {
            TournamentService service = tournamentService;
            
            
            
            Tournament tournament = Tournament.builder("Small")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .build();
            
            for (int i = 0; i < 6; i++) {
                tournament.registerPlayer(UUID.randomUUID(), "Player" + i);
            }
            tournament.start();
            
            assertThat(tournament.getTables()).hasSize(1);
        }
        
        @Test
        @DisplayName("should create multiple tables for large field")
        void shouldCreateMultipleTablesForLargeField() {
            Tournament tournament = Tournament.builder("Large")
                .type(TournamentType.MULTI_TABLE)
                .players(10, 100)
                .build();
            
            for (int i = 0; i < 20; i++) {
                tournament.registerPlayer(UUID.randomUUID(), "Player" + i);
            }
            tournament.start();
            
            
            assertThat(tournament.getTables().size()).isGreaterThanOrEqualTo(2);
        }
        
        @Test
        @DisplayName("should rebalance tables when called")
        void shouldRebalanceTablesWhenCalled() {
            UUID tournamentId = UUID.randomUUID();
            Tournament tournament = Tournament.builder("Rebalance Test")
                .type(TournamentType.FREEZEOUT)
                .players(2, 18)
                .build();
            
            for (int i = 0; i < 12; i++) {
                tournament.registerPlayer(UUID.randomUUID(), "Player" + i);
            }
            tournament.start();
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(tableRepository.findActiveTablesByTournament(tournamentId))
                .thenReturn(new ArrayList<>(tournament.getActiveTables()));
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(tableRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
            
            tournamentService.rebalanceTables(tournamentId);
            
            
            List<TournamentTable> tables = tournament.getActiveTables();
            if (tables.size() > 1) {
                int min = tables.stream().mapToInt(TournamentTable::getPlayerCount).min().orElse(0);
                int max = tables.stream().mapToInt(TournamentTable::getPlayerCount).max().orElse(0);
                assertThat(max - min).isLessThanOrEqualTo(1);
            }
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Player Elimination")
    class PlayerEliminationTests {
        
        private Tournament tournament;
        private UUID tournamentId;
        private UUID player1Id, player2Id, player3Id;
        
        @BeforeEach
        void setUp() {
            tournamentId = UUID.randomUUID();
            player1Id = UUID.randomUUID();
            player2Id = UUID.randomUUID();
            player3Id = UUID.randomUUID();
            
            tournament = Tournament.builder("Elimination Test")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            
            tournament.registerPlayer(player1Id, "Player1");
            tournament.registerPlayer(player2Id, "Player2");
            tournament.registerPlayer(player3Id, "Player3");
            tournament.start();
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(tableRepository.findActiveTablesByTournament(tournamentId))
                .thenReturn(new ArrayList<>(tournament.getActiveTables()));
            when(tableRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        }
        
        @Test
        @DisplayName("should eliminate player")
        void shouldEliminatePlayer() {
            tournamentService.handlePlayerElimination(tournamentId, player1Id);
            
            TournamentRegistration eliminated = tournament.findRegistration(player1Id).orElseThrow();
            assertThat(eliminated.getStatus()).isEqualTo(RegistrationStatus.ELIMINATED);
            assertThat(eliminated.getFinishPosition()).isEqualTo(3);
        }
        
        @Test
        @DisplayName("should publish TournamentPlayerEliminated event")
        void shouldPublishEliminationEvent() {
            tournamentService.handlePlayerElimination(tournamentId, player1Id);
            
            verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
            
            boolean hasEliminationEvent = eventCaptor.getAllValues().stream()
                .anyMatch(e -> e instanceof TournamentPlayerEliminated);
            assertThat(hasEliminationEvent).isTrue();
        }
        
        @Test
        @DisplayName("should calculate prize for eliminated player")
        void shouldCalculatePrize() {
            
            tournamentService.handlePlayerElimination(tournamentId, player1Id);
            
            TournamentRegistration eliminated = tournament.findRegistration(player1Id).orElseThrow();
            
            assertThat(eliminated.getPrizeWon()).isEqualTo(60);
        }
        
        @Test
        @DisplayName("should end tournament when one player remains")
        void shouldEndTournamentWithOneRemaining() {
            tournamentService.handlePlayerElimination(tournamentId, player1Id);
            tournamentService.handlePlayerElimination(tournamentId, player2Id);
            
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.COMPLETED);
            verify(eventPublisher).publishEvent(any(TournamentCompleted.class));
        }
        
        @Test
        @DisplayName("should reject eliminating already eliminated player")
        void shouldRejectDoubleElimination() {
            tournamentService.handlePlayerElimination(tournamentId, player1Id);
            
            assertThatThrownBy(() -> 
                tournamentService.handlePlayerElimination(tournamentId, player1Id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already eliminated");
        }
        
        @Test
        @DisplayName("should track bounty in bounty tournament")
        void shouldTrackBountyInBountyTournament() {
            
            Tournament bountyTournament = Tournament.builder("Bounty Test")
                .type(TournamentType.BOUNTY)
                .players(2, 9)
                .buyIn(100)
                .bounty(50)
                .build();
            
            UUID bountyTournamentId = UUID.randomUUID();
            UUID eliminatorId = UUID.randomUUID();
            UUID eliminatedId = UUID.randomUUID();
            
            bountyTournament.registerPlayer(eliminatorId, "Eliminator");
            bountyTournament.registerPlayer(eliminatedId, "Eliminated");
            bountyTournament.registerPlayer(UUID.randomUUID(), "Other");
            bountyTournament.start();
            
            when(tournamentRepository.findById(bountyTournamentId))
                .thenReturn(Optional.of(bountyTournament));
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(tableRepository.findActiveTablesByTournament(bountyTournamentId))
                .thenReturn(new ArrayList<>(bountyTournament.getActiveTables()));
            when(tableRepository.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
            
            tournamentService.handlePlayerElimination(bountyTournamentId, eliminatedId, eliminatorId);
            
            TournamentRegistration eliminator = bountyTournament.findRegistration(eliminatorId).orElseThrow();
            assertThat(eliminator.getBountiesCollected()).isEqualTo(1);
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Tournament End")
    class TournamentEndTests {
        
        @Test
        @DisplayName("should declare winner on tournament end")
        void shouldDeclareWinner() {
            UUID tournamentId = UUID.randomUUID();
            UUID winnerId = UUID.randomUUID();
            
            Tournament tournament = Tournament.builder("End Test")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            
            tournament.registerPlayer(winnerId, "Winner");
            tournament.registerPlayer(UUID.randomUUID(), "Loser");
            tournament.start();
            
            
            tournament.eliminatePlayer(
                tournament.getRegistrations().stream()
                    .filter(r -> !r.getPlayerId().equals(winnerId))
                    .findFirst()
                    .orElseThrow()
                    .getPlayerId()
            );
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            
            tournamentService.endTournament(tournamentId);
            
            TournamentRegistration winner = tournament.findRegistration(winnerId).orElseThrow();
            assertThat(winner.getFinishPosition()).isEqualTo(1);
            assertThat(winner.getPrizeWon()).isGreaterThan(0);
        }
        
        @Test
        @DisplayName("should publish TournamentCompleted event")
        void shouldPublishCompletedEvent() {
            UUID tournamentId = UUID.randomUUID();
            
            Tournament tournament = Tournament.builder("Complete Test")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            
            tournament.registerPlayer(UUID.randomUUID(), "Winner");
            tournament.registerPlayer(UUID.randomUUID(), "Loser");
            tournament.start();
            
            
            tournament.eliminatePlayer(tournament.getRegistrations().get(1).getPlayerId());
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            when(tournamentRepository.save(any(Tournament.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            
            tournamentService.endTournament(tournamentId);
            
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isInstanceOf(TournamentCompleted.class);
            
            TournamentCompleted event = (TournamentCompleted) eventCaptor.getValue();
            assertThat(event.getWinnerName()).isEqualTo("Winner");
            assertThat(event.getTotalPlayers()).isEqualTo(2);
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Query Methods")
    class QueryTests {
        
        @Test
        @DisplayName("should get tournament by ID")
        void shouldGetTournamentById() {
            UUID tournamentId = UUID.randomUUID();
            Tournament tournament = Tournament.builder("Query Test")
                .type(TournamentType.FREEZEOUT)
                .build();
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            
            Tournament result = tournamentService.getTournament(tournamentId);
            
            assertThat(result).isEqualTo(tournament);
        }
        
        @Test
        @DisplayName("should throw when tournament not found")
        void shouldThrowWhenNotFound() {
            UUID tournamentId = UUID.randomUUID();
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.empty());
            
            assertThatThrownBy(() -> tournamentService.getTournament(tournamentId))
                .isInstanceOf(ResourceNotFoundException.class);
        }
        
        @Test
        @DisplayName("should get open tournaments")
        void shouldGetOpenTournaments() {
            List<Tournament> expected = List.of(
                Tournament.builder("Open 1").type(TournamentType.FREEZEOUT).build(),
                Tournament.builder("Open 2").type(TournamentType.SIT_AND_GO).build()
            );
            
            when(tournamentRepository.findOpenTournaments()).thenReturn(expected);
            
            List<Tournament> result = tournamentService.getOpenTournaments();
            
            assertThat(result).hasSize(2);
        }
        
        @Test
        @DisplayName("should get running tournaments")
        void shouldGetRunningTournaments() {
            when(tournamentRepository.findRunningTournaments())
                .thenReturn(Collections.emptyList());
            
            List<Tournament> result = tournamentService.getRunningTournaments();
            
            assertThat(result).isEmpty();
            verify(tournamentRepository).findRunningTournaments();
        }
        
        @Test
        @DisplayName("should get leaderboard")
        void shouldGetLeaderboard() {
            UUID tournamentId = UUID.randomUUID();
            List<TournamentRegistration> expected = new ArrayList<>();
            
            when(registrationRepository.findByTournamentIdOrderByChipsDesc(tournamentId))
                .thenReturn(expected);
            
            List<TournamentRegistration> result = tournamentService.getLeaderboard(tournamentId);
            
            assertThat(result).isEqualTo(expected);
        }
    }
    
    
    
    
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("should handle heads-up transition")
        void shouldHandleHeadsUpTransition() {
            UUID tournamentId = UUID.randomUUID();
            Tournament tournament = Tournament.builder("HeadsUp Test")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .buyIn(100)
                .build();
            
            for (int i = 0; i < 4; i++) {
                tournament.registerPlayer(UUID.randomUUID(), "Player" + i);
            }
            tournament.start();
            
            
            List<TournamentRegistration> regs = new ArrayList<>(tournament.getActiveRegistrations());
            tournament.eliminatePlayer(regs.get(0).getPlayerId());
            tournament.eliminatePlayer(regs.get(1).getPlayerId());
            
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.HEADS_UP);
        }
        
        @Test
        @DisplayName("should handle empty tournament gracefully")
        void shouldHandleEmptyTournament() {
            UUID tournamentId = UUID.randomUUID();
            Tournament tournament = Tournament.builder("Empty Test")
                .type(TournamentType.FREEZEOUT)
                .players(2, 9)
                .build();
            
            when(tournamentRepository.findById(tournamentId))
                .thenReturn(Optional.of(tournament));
            
            assertThatThrownBy(() -> tournamentService.startTournament(tournamentId))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
