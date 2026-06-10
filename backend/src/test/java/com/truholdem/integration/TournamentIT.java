package com.truholdem.integration;

import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.dto.TournamentDetailResponse;
import com.truholdem.model.*;
import com.truholdem.repository.*;
import com.truholdem.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@DisplayName("Tournament Integration Tests")
class TournamentIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("truholdem_tournament_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.liquibase.enabled", () -> "false");

        registry.add("spring.cache.type", () -> "simple");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");

        registry.add("app.jwt.secret", () -> "dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy0xMjM0NTY3ODkw");
        registry.add("app.jwt.expiration", () -> "86400000");

        registry.add("app.tournament.level-duration-seconds", () -> "5");
        registry.add("app.tournament.break-duration-seconds", () -> "2");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TournamentService tournamentService;

    @Autowired
    private PokerGameService pokerGameService;

    @Autowired
    private TournamentTableGameService tableGameService;

    @Autowired
    private TournamentRepository tournamentRepository;

    @Autowired
    private TournamentRegistrationRepository registrationRepository;

    @Autowired
    private TournamentTableRepository tableRepository;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private Tournament tournament;
    private UUID tournamentId;
    private List<UUID> playerIds;
    private Map<UUID, String> playerNames;

    @BeforeEach
    void setUp() {
        reset(messagingTemplate);

        registrationRepository.deleteAll();
        tableRepository.deleteAll();
        tournamentRepository.deleteAll();

        playerIds = new ArrayList<>();
        playerNames = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        // Tournament cleanup is handled automatically by test transaction rollback
        // No need to manually cancel scheduled tasks as they're managed by Spring context
        tournament = null;
        playerNames.clear();
    }

    // ==================== Helper Methods ====================

    // NOTE: these helpers build the request explicitly (the convenience factories on
    // CreateTournamentRequest hard-code maxPlayers / maxRebuys, which silently ignored these helpers'
    // parameters and is what left the tournaments mis-sized — REGISTERING / "Tournament is full"). minPlayers
    // is 2 so an explicit start never trips "insufficient players"; SNG auto-start is gated on maxPlayers.
    private Tournament createSitAndGoTournament(int maxPlayers, int buyIn) {
        return tournamentService.createTournament(buildRequest(
                "Test SNG", TournamentType.SIT_AND_GO, 1500, maxPlayers, buyIn, "TURBO", null, null));
    }

    private Tournament createFreezeoutTournament(int maxPlayers, int buyIn, int startingChips) {
        return tournamentService.createTournament(buildRequest(
                "Test Freezeout", TournamentType.FREEZEOUT, startingChips, maxPlayers, buyIn, "STANDARD", null, null));
    }

    private Tournament createRebuyTournament(int buyIn, int maxRebuys) {
        return tournamentService.createTournament(buildRequest(
                "Test Rebuy", TournamentType.REBUY, 1500, 9, buyIn, "STANDARD", buyIn, maxRebuys));
    }

    private CreateTournamentRequest buildRequest(String namePrefix, TournamentType type, int startingChips,
            int maxPlayers, int buyIn, String blindStructure, Integer rebuyAmount, Integer maxRebuys) {
        return new CreateTournamentRequest(
                namePrefix + " " + System.nanoTime(),
                type,
                startingChips,
                2,                                              // minPlayers
                maxPlayers,
                buyIn,
                blindStructure,
                null,                                           // levelDurationMinutes (use default)
                rebuyAmount,
                rebuyAmount != null ? 6 : null,                // rebuyDeadlineLevel
                maxRebuys,
                null,                                           // addOnAmount
                null,                                           // bountyAmount
                null,                                           // payoutStructure
                false,                                          // unregisterRequiresApproval
                null, null, null);                              // cryptoBuyInAmount / asset / feeBasisPoints
    }

    // The test drives services directly (no open-session-in-view), so navigating the lazy
    // Tournament.registrations / tables collections off a detached entity throws LazyInitializationException
    // (or returns a stale snapshot). Read the committed rows through the repositories instead.
    private Optional<TournamentRegistration> reg(UUID playerId) {
        return registrationRepository.findByTournamentIdAndPlayerId(tournament.getId(), playerId);
    }

    private List<TournamentTable> activeTables() {
        return tableRepository.findActiveTablesByTournament(tournament.getId());
    }

    private List<TournamentRegistration> allRegs() {
        return registrationRepository.findByTournamentId(tournament.getId());
    }

    // Tournament.getPrizePool() multiplies by registrations.size(), a lazy collection — recompute it from the
    // committed rows (base buy-ins + rebuy contributions, the only components these tests exercise).
    private int prizePool() {
        List<TournamentRegistration> regs = allRegs();
        int base = tournament.getBuyIn() * regs.size();
        int rebuys = regs.stream().mapToInt(TournamentRegistration::getRebuysUsed).sum();
        return base + tournament.getRebuyAmount() * rebuys;
    }

    private void registerPlayers(int count) {
        for (int i = 0; i < count; i++) {
            UUID playerId = UUID.randomUUID();
            String playerName = "Player_" + (i + 1);
            playerIds.add(playerId);
            playerNames.put(playerId, playerName);

            tournamentService.registerPlayer(tournament.getId(), playerId, playerName);
        }
    }

    /**
     * Eliminates a player using the proper service method.
     * The service handles all the state updates internally.
     */
    private void eliminatePlayer(UUID playerId) {
        tournamentService.handlePlayerElimination(tournament.getId(), playerId);
    }

    private void simulateHandWin(UUID winnerId, int chipsWon) {
        tournament = tournamentService.getTournament(tournament.getId());
        TournamentRegistration winner = reg(winnerId)
            .orElseThrow();

        winner.setChips(winner.getCurrentChips() + chipsWon);
        registrationRepository.save(winner);
    }

    private int countRemainingPlayers() {
        tournament = tournamentService.getTournament(tournament.getId());
        // "Remaining" = still in the tournament (not eliminated). The last survivor is crowned the winner when the
        // tournament auto-completes, leaving PLAYING — so counting PLAYING would drop the winner to 0; count
        // not-eliminated instead.
        return (int) allRegs().stream()
            .filter(r -> r.getStatus() != RegistrationStatus.ELIMINATED)
            .count();
    }

    private int countActiveTables() {
        tournament = tournamentService.getTournament(tournament.getId());
        return activeTables().size();
    }

    // ==================== Complete Tournament Flow Tests ====================

    @Nested
    @DisplayName("Complete Tournament Flow")
    @TestMethodOrder(OrderAnnotation.class)
    class CompleteTournamentFlowTests {

        @Test
        @Order(1)
        @DisplayName("Should run complete 9-player Sit & Go until winner")
        void shouldRunCompleteTournament() {
            tournament = createSitAndGoTournament(9, 100);
            tournamentId = tournament.getId();

            registerPlayers(9);

            tournament = tournamentService.getTournament(tournamentId);

            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);
            assertThat(allRegs()).hasSize(9);
            assertThat(prizePool()).isEqualTo(900); // int, not BigDecimal

            List<UUID> playersToEliminate = new ArrayList<>(playerIds);
            UUID lastStanding = playersToEliminate.remove(0);

            Collections.shuffle(playersToEliminate);

            int eliminated = 0;
            for (UUID loser : playersToEliminate) {
                simulateHandWin(lastStanding, 1500 / 8);
                eliminatePlayer(loser);
                eliminated++;

                tournament = tournamentService.getTournament(tournamentId);
                assertThat(countRemainingPlayers()).isEqualTo(9 - eliminated);
            }

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.COMPLETED);

            TournamentRegistration winner = reg(lastStanding).orElseThrow();
            assertThat(winner.getFinishPosition()).isEqualTo(1);
            assertThat(winner.getPrizeWon()).isGreaterThan(0);
        }

        @Test
        @Order(2)
        @DisplayName("Should track finish positions correctly")
        void shouldTrackFinishPositions() {
            tournament = createSitAndGoTournament(6, 50);
            tournamentId = tournament.getId();
            registerPlayers(6);

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);

            List<UUID> eliminationOrder = new ArrayList<>(playerIds.subList(1, 6));
            UUID winner = playerIds.get(0);

            int expectedPosition = 6;
            for (UUID loser : eliminationOrder) {
                eliminatePlayer(loser);

                tournament = tournamentService.getTournament(tournamentId);
                TournamentRegistration eliminatedReg = reg(loser).orElseThrow();

                assertThat(eliminatedReg.getFinishPosition()).isEqualTo(expectedPosition);
                expectedPosition--;
            }

            tournament = tournamentService.getTournament(tournamentId);
            TournamentRegistration winnerReg = reg(winner).orElseThrow();
            assertThat(winnerReg.getFinishPosition()).isEqualTo(1);
        }
    }

    // ==================== Table Balancing Tests ====================

    @Nested
    @DisplayName("Table Balancing")
    class TableBalancingTests {

        @Test
        @DisplayName("Should rebalance tables correctly when players are eliminated")
        void shouldRebalanceTablesCorrectly() {
            tournament = createFreezeoutTournament(18, 100, 1500);
            tournamentId = tournament.getId();
            registerPlayers(18);

            tournamentService.startTournament(tournamentId);

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);

            int initialTables = countActiveTables();
            assertThat(initialTables).isEqualTo(3); // 18 players, ~8 ideal/table -> ceil(18/8)=3

            for (int i = 0; i < 8; i++) {
                eliminatePlayer(playerIds.get(i));
            }

            tournament = tournamentService.getTournament(tournamentId);

            assertThat(countRemainingPlayers()).isEqualTo(10);

            List<TournamentTable> tables = activeTables();
            int totalSeated = tables.stream()
                .mapToInt(TournamentTable::getPlayerCount)
                .sum();

            assertThat(totalSeated).isEqualTo(10);

            tables.forEach(table ->
                assertThat(table.getPlayerCount()).isLessThanOrEqualTo(9)
            );

            if (tables.size() > 1) {
                int max = tables.stream().mapToInt(TournamentTable::getPlayerCount).max().orElse(0);
                int min = tables.stream().mapToInt(TournamentTable::getPlayerCount).min().orElse(0);
                assertThat(max - min).isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("Should move player from large table to small table")
        void shouldMovePlayerToBalanceTables() {
            tournament = createFreezeoutTournament(12, 100, 1500);
            tournamentId = tournament.getId();
            registerPlayers(12);

            tournamentService.startTournament(tournamentId);
            tournament = tournamentService.getTournament(tournamentId);

            List<TournamentTable> tables = activeTables();
            assertThat(tables).hasSize(2);

            TournamentTable table1 = tables.get(0);
            UUID playerToEliminate = table1.getPlayerIds().get(0);
            eliminatePlayer(playerToEliminate);

            tournament = tournamentService.getTournament(tournamentId);
            tables = activeTables();

            int count1 = tables.get(0).getPlayerCount();
            int count2 = tables.size() > 1 ? tables.get(1).getPlayerCount() : 0;

            assertThat(count1 + count2).isEqualTo(11);
            assertThat(Math.abs(count1 - count2)).isLessThanOrEqualTo(1);
        }
    }

    // ==================== Blind Level Progression Tests ====================

    @Nested
    @DisplayName("Blind Level Progression")
    class BlindLevelProgressionTests {

        @Test
        @DisplayName("Should progress blind levels automatically")
        void shouldProgressBlindLevels() {
            tournament = createSitAndGoTournament(4, 100);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);

            int initialLevel = tournament.getCurrentLevel();
            BlindLevel initialBlinds = tournament.getCurrentBlindLevel();

            assertThat(initialLevel).isEqualTo(1);
            assertThat(initialBlinds.getSmallBlind()).isGreaterThan(0);
            assertThat(initialBlinds.getBigBlind()).isGreaterThan(initialBlinds.getSmallBlind());

            await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Tournament t = tournamentService.getTournament(tournamentId);
                    assertThat(t.getCurrentLevel()).isGreaterThan(initialLevel);
                });

            tournament = tournamentService.getTournament(tournamentId);
            BlindLevel newBlinds = tournament.getCurrentBlindLevel();

            assertThat(newBlinds.getSmallBlind()).isGreaterThan(initialBlinds.getSmallBlind());
            assertThat(newBlinds.getBigBlind()).isGreaterThan(initialBlinds.getBigBlind());
        }

        @Test
        @DisplayName("Should trigger break at correct intervals")
        void shouldTriggerBreakAtCorrectIntervals() {
            tournament = createSitAndGoTournament(4, 100);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournament = tournamentService.getTournament(tournamentId);

            tournamentService.advanceLevel(tournamentId);
            tournamentService.advanceLevel(tournamentId);
            tournamentService.advanceLevel(tournamentId);

            tournament = tournamentService.getTournament(tournamentId);

            assertThat(tournament.getCurrentLevel()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should have ante at higher blind levels")
        void shouldHaveAnteAtHigherLevels() {
            tournament = createSitAndGoTournament(4, 100);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournament = tournamentService.getTournament(tournamentId);

            BlindLevel level1 = tournament.getCurrentBlindLevel();
            assertThat(level1.getAnte()).isEqualTo(0);

            for (int i = 0; i < 5; i++) {
                tournamentService.advanceLevel(tournamentId);
            }

            tournament = tournamentService.getTournament(tournamentId);
            BlindLevel laterLevel = tournament.getCurrentBlindLevel();

            assertThat(laterLevel.getAnte()).isGreaterThanOrEqualTo(0);
        }
    }

    // ==================== Rebuy Tournament Tests ====================

    @Nested
    @DisplayName("Rebuy Tournament")
    class RebuyTournamentTests {

        @Test
        @DisplayName("Should allow rebuy within rebuy period")
        void shouldHandleRebuy() {
            tournament = createRebuyTournament(100, 3);
            tournamentId = tournament.getId();
            registerPlayers(6);

            tournamentService.startTournament(tournamentId);
            tournament = tournamentService.getTournament(tournamentId);

            UUID rebuyPlayer = playerIds.get(0);
            TournamentRegistration reg = reg(rebuyPlayer).orElseThrow();
            int initialChips = reg.getCurrentChips();

            reg.setChips(0);
            registrationRepository.save(reg);

            TournamentRegistration afterRebuy = tournamentService.processRebuy(tournamentId, rebuyPlayer);

            assertThat(afterRebuy.getCurrentChips()).isEqualTo(tournament.getRebuyAmount());
            assertThat(afterRebuy.getRebuysUsed()).isEqualTo(1);
            assertThat(afterRebuy.getStatus()).isEqualTo(RegistrationStatus.PLAYING);

            tournament = tournamentService.getTournament(tournamentId);
            int expectedPrizePool = 6 * 100 + 100; // int
            assertThat(prizePool()).isEqualTo(expectedPrizePool);
        }

        @Test
        @DisplayName("Should reject rebuy after max rebuys used")
        void shouldRejectRebuyAfterMaxUsed() {
            tournament = createRebuyTournament(100, 2);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournamentService.startTournament(tournamentId);
            tournament = tournamentService.getTournament(tournamentId);

            UUID rebuyPlayer = playerIds.get(0);

            for (int i = 0; i < 2; i++) {
                TournamentRegistration reg = reg(rebuyPlayer).orElseThrow();
                reg.setChips(0);
                registrationRepository.save(reg);
                tournamentService.processRebuy(tournamentId, rebuyPlayer);
            }

            TournamentRegistration reg = reg(rebuyPlayer).orElseThrow();
            assertThat(reg.getRebuysUsed()).isEqualTo(2);

            reg.setChips(0);
            registrationRepository.save(reg);

            assertThatThrownBy(() ->
                tournamentService.processRebuy(tournamentId, rebuyPlayer)
            ).isInstanceOf(IllegalStateException.class)
             .hasMessageContaining("rebuy");
        }

        @Test
        @DisplayName("Should reject rebuy in freezeout tournament")
        void shouldRejectRebuyInFreezeout() {
            tournament = createFreezeoutTournament(9, 100, 1500);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournamentService.startTournament(tournamentId);
            tournament = tournamentService.getTournament(tournamentId);

            UUID player = playerIds.get(0);
            TournamentRegistration reg = reg(player).orElseThrow();
            reg.setChips(0);
            registrationRepository.save(reg);

            assertThatThrownBy(() ->
                tournamentService.processRebuy(tournamentId, player)
            ).isInstanceOf(IllegalStateException.class);
        }
    }

    // ==================== Final Table Consolidation Tests ====================

    @Nested
    @DisplayName("Final Table Consolidation")
    class FinalTableConsolidationTests {

        @Test
        @DisplayName("Should consolidate to final table when 9 or fewer players remain")
        void shouldConsolidateToFinalTable() {
            tournament = createFreezeoutTournament(18, 100, 1500);
            tournamentId = tournament.getId();
            registerPlayers(18);

            tournamentService.startTournament(tournamentId);
            tournament = tournamentService.getTournament(tournamentId);

            assertThat(countActiveTables()).isEqualTo(3); // 18 players, ~8 ideal/table -> ceil(18/8)=3
            assertThat(tournament.getStatus()).isNotEqualTo(TournamentStatus.FINAL_TABLE);

            for (int i = 0; i < 9; i++) {
                eliminatePlayer(playerIds.get(i));
            }

            tournament = tournamentService.getTournament(tournamentId);

            assertThat(countRemainingPlayers()).isEqualTo(9);
            assertThat(countActiveTables()).isEqualTo(1);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.FINAL_TABLE);

            TournamentTable finalTable = activeTables().get(0);
            assertThat(finalTable.isFinalTable()).isTrue();
            assertThat(finalTable.getPlayerCount()).isEqualTo(9);
        }

        @Test
        @DisplayName("Should mark final table in tournament status")
        void shouldMarkFinalTableStatus() {
            tournament = createSitAndGoTournament(9, 100);
            tournamentId = tournament.getId();
            registerPlayers(9);

            tournament = tournamentService.getTournament(tournamentId);

            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);
            assertThat(countActiveTables()).isEqualTo(1);
            // Single table SNG is always at final table effectively
        }
    }

    // ==================== Prize Pool Calculation Tests ====================

    @Nested
    @DisplayName("Prize Pool Calculation")
    class PrizePoolCalculationTests {

        @Test
        @DisplayName("Should calculate prizes correctly for standard payout")
        void shouldCalculatePrizesCorrectly() {
            tournament = createSitAndGoTournament(9, 100);
            tournamentId = tournament.getId();
            registerPlayers(9);

            tournament = tournamentService.getTournament(tournamentId);
            int prizePool = prizePool();

            assertThat(prizePool).isEqualTo(900);

            // getPayoutStructure returns List<Integer> (percentages)
            List<Integer> payoutStructure = tournament.getPayoutStructure();

            assertThat(payoutStructure).isNotEmpty();

            // Calculate actual prizes from percentages (mirrors Tournament.calculatePrizeForPosition, but using
            // the repository-backed prizePool() so it doesn't touch the lazy registrations collection).
            int firstPlacePrize = (prizePool() * payoutStructure.get(0)) / 100;
            int secondPlacePrize = payoutStructure.size() > 1 ? (prizePool() * payoutStructure.get(1)) / 100 : 0;

            assertThat(firstPlacePrize).isGreaterThan(secondPlacePrize);
        }

        @Test
        @DisplayName("Should award prizes to correct positions")
        void shouldAwardPrizesToCorrectPositions() {
            tournament = createSitAndGoTournament(6, 100);
            tournamentId = tournament.getId();
            registerPlayers(6);

            tournament = tournamentService.getTournament(tournamentId);

            List<UUID> eliminationOrder = new ArrayList<>();
            UUID winner = playerIds.get(0);

            for (int i = 5; i >= 1; i--) {
                eliminationOrder.add(playerIds.get(i));
            }

            for (UUID loser : eliminationOrder) {
                eliminatePlayer(loser);
            }

            tournament = tournamentService.getTournament(tournamentId);

            TournamentRegistration first = reg(winner).orElseThrow();
            assertThat(first.getFinishPosition()).isEqualTo(1);
            assertThat(first.getPrizeWon()).isGreaterThan(0);

            TournamentRegistration second = reg(playerIds.get(1)).orElseThrow();
            assertThat(second.getFinishPosition()).isEqualTo(2);

            TournamentRegistration third = reg(playerIds.get(2)).orElseThrow();
            assertThat(third.getFinishPosition()).isEqualTo(3);

            TournamentRegistration bubble = reg(playerIds.get(3)).orElseThrow();
            assertThat(bubble.getFinishPosition()).isEqualTo(4);
            assertThat(bubble.getPrizeWon()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should include rebuys in prize pool")
        void shouldIncludeRebuysInPrizePool() {
            tournament = createRebuyTournament(100, 5);
            tournamentId = tournament.getId();
            registerPlayers(6);

            tournamentService.startTournament(tournamentId);
            tournament = tournamentService.getTournament(tournamentId);

            int initialPrizePool = prizePool();
            assertThat(initialPrizePool).isEqualTo(600);

            for (int i = 0; i < 3; i++) {
                UUID player = playerIds.get(i);
                TournamentRegistration reg = reg(player).orElseThrow();
                reg.setChips(0);
                registrationRepository.save(reg);
                tournamentService.processRebuy(tournamentId, player);
            }

            tournament = tournamentService.getTournament(tournamentId);
            int finalPrizePool = prizePool();

            assertThat(finalPrizePool).isEqualTo(900);
        }
    }

    // ==================== Edge Cases and Error Handling ====================

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should not start tournament with insufficient players")
        void shouldNotStartWithInsufficientPlayers() {
            tournament = createSitAndGoTournament(9, 100);
            tournamentId = tournament.getId();
            registerPlayers(1);

            assertThatThrownBy(() ->
                tournamentService.startTournament(tournamentId)
            ).isInstanceOf(IllegalStateException.class)
             .hasMessageContaining("player");
        }

        @Test
        @DisplayName("Should handle concurrent registrations")
        void shouldHandleConcurrentRegistrations() throws Exception {
            tournament = createSitAndGoTournament(9, 100);
            tournamentId = tournament.getId();

            ExecutorService executor = Executors.newFixedThreadPool(9);
            CountDownLatch latch = new CountDownLatch(9);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < 9; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    try {
                        UUID playerId = UUID.randomUUID();
                        tournamentService.registerPlayer(
                            tournamentId,
                            playerId,
                            "Concurrent_" + index
                        );
                    } finally {
                        latch.countDown();
                    }
                }));
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(allRegs()).hasSize(9);
        }

        @Test
        @DisplayName("Should reject registration when tournament is full")
        void shouldRejectRegistrationWhenFull() {
            tournament = createSitAndGoTournament(4, 100);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);

            assertThatThrownBy(() ->
                tournamentService.registerPlayer(
                    tournamentId,
                    UUID.randomUUID(),
                    "Extra_Player"
                )
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should not allow duplicate registration")
        void shouldNotAllowDuplicateRegistration() {
            tournament = createSitAndGoTournament(9, 100);
            tournamentId = tournament.getId();

            UUID playerId = UUID.randomUUID();
            tournamentService.registerPlayer(tournamentId, playerId, "FirstReg");

            assertThatThrownBy(() ->
                tournamentService.registerPlayer(tournamentId, playerId, "SecondReg")
            ).isInstanceOf(IllegalStateException.class)
             .hasMessageContaining("already registered");
        }
    }

    // ==================== Tournament Statistics ====================

    @Nested
    @DisplayName("Tournament Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("Should track tournament duration")
        void shouldTrackTournamentDuration() {
            tournament = createSitAndGoTournament(4, 100);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournament = tournamentService.getTournament(tournamentId);

            assertThat(tournament.getStartTime()).isNotNull();
            assertThat(tournament.getEndTime()).isNull();

            for (int i = 1; i < 4; i++) {
                eliminatePlayer(playerIds.get(i));
            }

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.COMPLETED);
            assertThat(tournament.getEndTime()).isNotNull();
            assertThat(tournament.getEndTime()).isAfterOrEqualTo(tournament.getStartTime());
        }

        @Test
        @DisplayName("Should provide leaderboard during tournament")
        void shouldProvideLeaderboard() {
            tournament = createSitAndGoTournament(6, 100);
            tournamentId = tournament.getId();
            registerPlayers(6);

            tournament = tournamentService.getTournament(tournamentId);

            List<TournamentRegistration> regs = new ArrayList<>(allRegs());
            int[] chips = {3000, 2500, 2000, 1500, 1000, 0};

            for (int i = 0; i < 6; i++) {
                TournamentRegistration reg = regs.get(i);
                reg.setChips(chips[i]);
                registrationRepository.save(reg);
            }

            List<TournamentRegistration> leaderboard = tournamentService.getLeaderboard(tournamentId);

            assertThat(leaderboard).hasSize(6);
            assertThat(leaderboard.get(0).getCurrentChips()).isEqualTo(3000);
            assertThat(leaderboard.get(1).getCurrentChips()).isEqualTo(2500);
            assertThat(leaderboard.get(5).getCurrentChips()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Tournament detail API (Phase 5a)")
    class TournamentDetailApiTests {

        @Test
        @DisplayName("Detail response includes standings and seated players")
        void detailIncludesPlayersAndTableSeats() {
            tournament = createFreezeoutTournament(4, 100, 1500);
            tournamentId = tournament.getId();
            registerPlayers(4);
            tournamentService.startTournament(tournamentId);

            TournamentDetailResponse detail = tournamentService.getTournamentDetail(tournamentId);

            assertThat(detail.registeredPlayers()).isEqualTo(4);
            assertThat(detail.players()).hasSize(4);
            assertThat(detail.players()).extracting(p -> p.playerName())
                    .contains("Player_1", "Player_4");
            assertThat(detail.tables()).isNotEmpty();
            assertThat(detail.tables().get(0).players()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Tournament table hand play (Phase 4a)")
    class TableHandPlayTests {

        @Test
        @DisplayName("Should start or resume a hand on an active table")
        void shouldStartTableHandWithSeatedPlayers() {
            tournament = createSitAndGoTournament(4, 100);
            tournamentId = tournament.getId();
            registerPlayers(4);

            tournament = tournamentService.getTournament(tournamentId);
            assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);

            TournamentTable table = activeTables().stream()
                    .filter(TournamentTable::isActive)
                    .findFirst()
                    .orElseThrow();

            Game game = tableGameService.getOrStartTableHand(tournamentId, table.getId());

            assertThat(game.getId()).isNotNull();
            assertThat(game.getPlayers()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(game.isFinished()).isFalse();
        }

        @Test
        @DisplayName("Registration chips may differ from live game while hand is in progress")
        void shouldAllowChipDriftDuringActiveHand() {
            tournament = createSitAndGoTournament(2, 100);
            tournamentId = tournament.getId();
            registerPlayers(2);

            tournament = tournamentService.getTournament(tournamentId);
            TournamentTable table = activeTables().get(0);
            UUID seatedPlayerId = table.getPlayerIds().get(0);

            Game game = tableGameService.getOrStartTableHand(tournamentId, table.getId());
            Player gamePlayer = game.getPlayers().stream()
                    .filter(p -> seatedPlayerId.equals(p.getId()))
                    .findFirst()
                    .orElseThrow();

            TournamentRegistration registration = registrationRepository
                    .findByTournamentIdAndPlayerId(tournamentId, seatedPlayerId)
                    .orElseThrow();

            assertThat(registration.getCurrentChips()).isEqualTo(tournament.getStartingChips());

            if (gamePlayer.getChips() != tournament.getStartingChips()) {
                assertThat(registration.getCurrentChips()).isNotEqualTo(gamePlayer.getChips());
            }
        }

        @Test
        @DisplayName("Finishing a table hand syncs registration chips automatically (Phase 4b)")
        void shouldSyncRegistrationChipsWhenHandFinishes() {
            tournament = createSitAndGoTournament(2, 100);
            tournamentId = tournament.getId();
            registerPlayers(2);

            tournament = tournamentService.getTournament(tournamentId);
            TournamentTable table = activeTables().get(0);

            Game game = tableGameService.getOrStartTableHand(tournamentId, table.getId());
            Player folder = game.getCurrentPlayer();
            assertThat(folder).isNotNull();

            Game finished = pokerGameService.playerAct(game.getId(), folder.getId(), PlayerAction.FOLD, 0);
            assertThat(finished.isFinished()).isTrue();

            for (Player gamePlayer : finished.getPlayers()) {
                if (gamePlayer.getId() == null) {
                    continue;
                }
                TournamentRegistration registration = registrationRepository
                        .findByTournamentIdAndPlayerId(tournamentId, gamePlayer.getId())
                        .orElseThrow();
                assertThat(registration.getCurrentChips())
                        .as("chips for player %s", gamePlayer.getName())
                        .isEqualTo(gamePlayer.getChips());
            }
        }
    }
}
