package com.truholdem.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.domain.value.PotMath;
import com.truholdem.dto.ShowdownResult;
import com.truholdem.dto.ShowdownResult.WinnerInfo;
import com.truholdem.model.Card;
import com.truholdem.model.Deck;
import com.truholdem.model.Game;
import com.truholdem.model.HandRanking;
import com.truholdem.model.GamePhase;
import com.truholdem.model.HandLifecycleState;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.config.AppProperties;
import com.truholdem.config.BotMode;
import com.truholdem.config.GameEngine;
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.domain.event.DomainEventPublisher;
import com.truholdem.domain.event.HandCompleted;
import com.truholdem.domain.value.Chips;
import com.truholdem.mapper.PokerGameMapper;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.cluster.ClusterActionForwarder;
import com.truholdem.service.cluster.ClusterForwardException;
import com.truholdem.service.cluster.TableOwnershipService;
import com.truholdem.service.game.GameStateService;
import com.truholdem.service.game.HandLifecycleScheduling;
import com.truholdem.service.game.TableCommandDispatcher;
import com.truholdem.service.tournament.TournamentChipSyncService;
import com.truholdem.service.tournament.TournamentTableShardService;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PokerGameService {

    private static final Logger logger = LoggerFactory.getLogger(PokerGameService.class);

    private final GameStateService gameStateService;
    private final HandEvaluator handEvaluator;
    private final HandHistoryService handHistoryService;
    private final PlayerStatisticsService playerStatisticsService;
    private final GameNotificationService notificationService;
    private final AdvancedBotAIService botAIService;
    private final PassiveBotAIService passiveBotAIService;
    private final AppProperties appProperties;
    private final GameMetricsService metricsService;
    private final TournamentTableShardService tournamentTableShardService;
    private final TournamentChipSyncService tournamentChipSyncService;
    private final GameTurnTimeoutService turnTimeoutService;
    private final GameHandLifecycleService handLifecycleService;
    private final PokerGameMapper pokerGameMapper;
    private final TableCommandDispatcher commandDispatcher;
    private final DomainEventPublisher domainEventPublisher;
    private final TableOwnershipService ownership;
    private final ClusterActionForwarder clusterActionForwarder;
    private final PokerGameService self;

    public PokerGameService(
            GameStateService gameStateService,
            HandEvaluator handEvaluator,
            HandHistoryService handHistoryService,
            PlayerStatisticsService playerStatisticsService,
            GameNotificationService notificationService,
            AdvancedBotAIService botAIService,
            PassiveBotAIService passiveBotAIService,
            AppProperties appProperties,
            GameMetricsService metricsService,
            TournamentTableShardService tournamentTableShardService,
            TournamentChipSyncService tournamentChipSyncService,
            GameTurnTimeoutService turnTimeoutService,
            GameHandLifecycleService handLifecycleService,
            PokerGameMapper pokerGameMapper,
            TableCommandDispatcher commandDispatcher,
            DomainEventPublisher domainEventPublisher,
            TableOwnershipService ownership,
            ClusterActionForwarder clusterActionForwarder,
            @Lazy PokerGameService self) {
        this.gameStateService = gameStateService;
        this.handEvaluator = handEvaluator;
        this.handHistoryService = handHistoryService;
        this.playerStatisticsService = playerStatisticsService;
        this.notificationService = notificationService;
        this.botAIService = botAIService;
        this.passiveBotAIService = passiveBotAIService;
        this.appProperties = appProperties;
        this.metricsService = metricsService;
        this.tournamentTableShardService = tournamentTableShardService;
        this.tournamentChipSyncService = tournamentChipSyncService;
        this.turnTimeoutService = turnTimeoutService;
        this.handLifecycleService = handLifecycleService;
        this.pokerGameMapper = pokerGameMapper;
        this.commandDispatcher = commandDispatcher;
        this.domainEventPublisher = domainEventPublisher;
        this.ownership = ownership;
        this.clusterActionForwarder = clusterActionForwarder;
        this.self = self;
    }

    /** Publish and drain the events the aggregate raised during the current command (Phase 3, CQRS). */
    private void publishDomainEvents(PokerGame aggregate) {
        domainEventPublisher.publishAll(aggregate.getDomainEvents());
        aggregate.clearDomainEvents();
    }

    public Game createNewGame(List<PlayerInfo> playersInfo) {
        return createNewGame(playersInfo, null, null);
    }

    public Game createNewGame(List<PlayerInfo> playersInfo, Integer smallBlind, Integer bigBlind) {
        return metricsService.timeGameCreation(() -> {
            validatePlayerCount(playersInfo);

            if (usesAggregateEngine()) {
                return createNewGameViaAggregate(playersInfo, smallBlind, bigBlind);
            }

            // Log received player info
            logger.info("Received player info for game creation:");
            for (PlayerInfo info : playersInfo) {
                logger.info("  - Name: {}, isBot: {}, startingChips: {}",
                    info.getName(), info.isBot(), info.getStartingChips());
            }

            Game game = new Game();
            if (smallBlind != null && smallBlind > 0) {
                game.setSmallBlind(smallBlind);
            }
            if (bigBlind != null && bigBlind > 0) {
                game.setBigBlind(bigBlind);
                game.setMinRaiseAmount(bigBlind);
            }
            Deck deck = new Deck();
            deck.shuffle();

            for (int i = 0; i < playersInfo.size(); i++) {
                PlayerInfo info = playersInfo.get(i);
                Player player = new Player(info.getName(), info.getStartingChips(), info.isBot());
                if (info.getPlayerId() != null) {
                    // Tie ownership to the user (non-bots) via userId. Only reuse the id as the player PK when
                    // asked (tournaments need a stable seat id to map back to registrations); single-player keeps
                    // a fresh per-game id so a second /poker/start by the same user doesn't clash on players_pkey.
                    if (!info.isBot()) {
                        player.setUserId(info.getPlayerId());
                    }
                    if (info.isUseStableId()) {
                        player.setId(info.getPlayerId());
                    }
                }
                player.setSeatPosition(i);
                game.addPlayer(player);
                logger.info("Created player {} with isBot={}", player.getName(), player.isBot());

                // Statistics are a non-critical side effect — never let them fail game creation.
                try {
                    playerStatisticsService.startSession(info.getName());
                } catch (RuntimeException e) {
                    logger.warn("startSession failed for player {} — continuing game creation", info.getName(), e);
                }
            }

            dealHoleCards(game, deck);

            game.setDeck(deck.getCards());
            postBlinds(game);
            game.setPhase(GamePhase.PRE_FLOP);
            game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);

            // Log who is first to act
            Player firstToAct = game.getCurrentPlayer();
            logger.info("First to act after blinds: {} (isBot: {}, index: {})",
                firstToAct != null ? firstToAct.getName() : "null",
                firstToAct != null ? firstToAct.isBot() : "N/A",
                game.getCurrentPlayerIndex());

            Game savedGame = gameStateService.persistFullSync(game);

            handHistoryService.startRecording(savedGame);

            notificationService.broadcastGameUpdate(savedGame);
            turnTimeoutService.scheduleForCurrentTurn(savedGame);

            metricsService.incrementGamesCreated();
            metricsService.setActivePlayers(playersInfo.size());

            logger.info("Created new game {} with {} players", savedGame.getId(), playersInfo.size());
            return savedGame;
        });
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game playerAct(UUID gameId, UUID playerId, PlayerAction action, int amount) {
        return playerAct(gameId, (UUID) null, playerId, action, amount);
    }

    /**
     * Player action entry point with an explicit idempotency key. With cross-node routing enabled,
     * an action for a table owned by another node is forwarded over HTTP to that owner; otherwise it
     * runs on this node (single-writer queue when enabled). A duplicate {@code commandId} is applied once.
     */
    @CacheEvict(value = "games", key = "#gameId")
    public Game playerAct(UUID gameId, UUID commandId, UUID playerId, PlayerAction action, int amount) {
        if (!appProperties.getCluster().isRoutingEnabled()) {
            return playerActLocal(gameId, commandId, playerId, action, amount);
        }
        if (ownership.acquire(gameId)) {
            return playerActLocal(gameId, commandId, playerId, action, amount); // this node owns the table
        }
        String owner = ownership.currentOwner(gameId);
        try {
            clusterActionForwarder.forward(owner, gameId, commandId, playerId, action, amount);
        } catch (ClusterForwardException e) {
            // Owner may have died — try to claim the table once and process locally.
            if (ownership.acquire(gameId)) {
                logger.warn("Owner {} of game {} unreachable; claimed locally", owner, gameId);
                return playerActLocal(gameId, commandId, playerId, action, amount);
            }
            throw new IllegalStateException("Table owner unreachable for game " + gameId, e);
        }
        // Owner processed + persisted to shared hot-state; return the authoritative reload.
        return findGameById(gameId);
    }

    /**
     * Process an action on THIS node, with no cross-node routing (single-writer queue when enabled).
     * Called for locally-owned tables and by the internal endpoint for forwarded actions.
     */
    @CacheEvict(value = "games", key = "#gameId")
    public Game playerActLocal(UUID gameId, UUID commandId, UUID playerId, PlayerAction action, int amount) {
        if (!appProperties.getGame().isSingleWriterEnabled()) {
            return playerActInternal(gameId, playerId, action, amount);
        }
        // self (proxy) so @Transactional opens on the worker thread, not the caller's.
        return commandDispatcher.submit(gameId, commandId,
                () -> self.playerActInternal(gameId, playerId, action, amount));
    }

    public Game playerActInternal(UUID gameId, UUID playerId, PlayerAction action, int amount) {
        return metricsService.timeActionProcessing(() -> {
            if (usesAggregateEngine()) {
                return playerActViaAggregate(gameId, playerId, action, amount);
            }

            Game game = findGameById(gameId);
            Player player = findPlayerInGame(game, playerId);

            validatePlayerTurn(game, playerId);
            validatePlayerCanAct(player);

            logger.debug("Player {} performing action {} with amount {}", player.getName(), action, amount);

            metricsService.recordPlayerAction(action.name());

            int actualAmount = 0;
            switch (action) {
                case FOLD -> {
                    handleFold(game, player);
                    metricsService.incrementFolds();
                }
                case CHECK -> handleCheck(game, player);
                case CALL -> actualAmount = handleCall(game, player);
                case BET -> actualAmount = handleBet(game, player, amount);
                case RAISE -> actualAmount = handleRaise(game, player, amount);
                case ALL_IN -> actualAmount = handleAllIn(game, player);
            }

            player.setHasActed(true);

            handHistoryService.recordAction(gameId, player, action, actualAmount, game.getPhase());
            playerStatisticsService.recordAction(player.getName(), action.name());

            if (player.isAllIn()) {
                playerStatisticsService.recordAllIn(player.getName());
            }

            notificationService.broadcastPlayerAction(game, player, action.name(), actualAmount);

            advanceGame(game);

            // Log the next player after advancing
            Player nextPlayer = game.getCurrentPlayer();
            logger.info("After {} by {}: next player is {} (isBot: {}, index: {})",
                action, player.getName(),
                nextPlayer != null ? nextPlayer.getName() : "null",
                nextPlayer != null ? nextPlayer.isBot() : "N/A",
                game.getCurrentPlayerIndex());

            return persistAfterAction(game);
        });
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game executeBotAction(UUID gameId, UUID botId) {
        return executeBotAction(gameId, null, botId);
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game executeBotAction(UUID gameId, UUID commandId, UUID botId) {
        if (!appProperties.getGame().isSingleWriterEnabled()) {
            return executeBotActionInternal(gameId, botId);
        }
        return commandDispatcher.submit(gameId, commandId, () -> self.executeBotActionInternal(gameId, botId));
    }

    public Game executeBotActionInternal(UUID gameId, UUID botId) {
        Game game = findGameById(gameId);
        Player bot = findPlayerInGame(game, botId);

        if (!bot.isBot()) {
            throw new IllegalStateException("Player is not a bot");
        }

        // Skip bot action if player is all-in or folded
        if (bot.isAllIn()) {
            logger.debug("Bot {} is all-in, skipping action and advancing to next player", bot.getName());
            advanceToNextPlayer(game);
            return persistAfterAction(game);
        }

        if (bot.isFolded()) {
            logger.debug("Bot {} is folded, skipping action and advancing to next player", bot.getName());
            advanceToNextPlayer(game);
            return persistAfterAction(game);
        }

        validatePlayerTurn(game, botId);

        AdvancedBotAIService.BotDecision decision = resolveBotDecision(game, bot);

        if (decision == null) {

            decision = new AdvancedBotAIService.BotDecision(
                    PlayerAction.CHECK,
                    0,
                    "fallback-null-decision");
        }

        // Safety check: if bot tries to CHECK but can't, convert to CALL or FOLD
        PlayerAction finalAction = decision.action();
        int finalAmount = decision.amount();

        if (finalAction == PlayerAction.CHECK && bot.getBetAmount() < game.getCurrentBet()) {
            int toCall = game.getCurrentBet() - bot.getBetAmount();
            if (bot.getChips() >= toCall) {
                logger.warn("Bot {} tried to CHECK but must CALL {} - converting action",
                        bot.getName(), toCall);
                finalAction = PlayerAction.CALL;
            } else {
                logger.warn("Bot {} tried to CHECK but must FOLD (insufficient chips) - converting action",
                        bot.getName());
                finalAction = PlayerAction.FOLD;
            }
        }

        logger.info("Bot {} decided: {} (amount: {}, reason: {})",
                bot.getName(), finalAction, finalAmount, decision.reasoning());

        // Already on this table's command (when single-writer is on) — call the core, never re-submit.
        return playerActInternal(gameId, botId, finalAction, finalAmount);
    }

    private AdvancedBotAIService.BotDecision resolveBotDecision(Game game, Player bot) {
        if (appProperties.getGame().getBotMode() == BotMode.PASSIVE) {
            return passiveBotAIService.decide(game, bot);
        }
        return botAIService.decide(game, bot);
    }

    @Cacheable(value = "games", key = "#gameId", unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<Game> getGame(UUID gameId) {
        try {
            return Optional.of(gameStateService.load(gameId));
        } catch (NoSuchElementException e) {
            return Optional.empty();
        }
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game startNewHand(UUID gameId) {
        return startNewHand(gameId, (UUID) null);
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game startNewHand(UUID gameId, UUID commandId) {
        if (!appProperties.getGame().isSingleWriterEnabled()) {
            return startNewHandInternal(gameId);
        }
        return commandDispatcher.submit(gameId, commandId, () -> self.startNewHandInternal(gameId));
    }

    public Game startNewHandInternal(UUID gameId) {
        handLifecycleService.cancel(gameId);

        if (usesAggregateEngine()) {
            return startNewHandViaAggregate(gameId);
        }

        Game game = findGameById(gameId);

        game.getPlayers().removeIf(p -> p.getChips() <= 0);

        if (game.getPlayers().size() < 2) {
            throw new IllegalStateException("Not enough players with chips to continue");
        }

        game.resetForNewHand();

        Deck deck = new Deck();
        deck.shuffle();

        dealHoleCards(game, deck);
        game.setDeck(deck.getCards());
        postBlinds(game);
        game.setPhase(GamePhase.PRE_FLOP);
        game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);

        logger.info("Started new hand {} in game {}", game.getHandNumber(), gameId);
        Game saved = gameStateService.persistFull(game);
        notificationService.broadcastGameUpdate(saved);
        turnTimeoutService.scheduleForCurrentTurn(saved);
        return saved;
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game transitionCompletedHandToResultDelay(UUID gameId, int expectedHandNumber) {
        if (!appProperties.getGame().isSingleWriterEnabled()) {
            return transitionCompletedHandToResultDelayInternal(gameId, expectedHandNumber);
        }
        return commandDispatcher.submit(gameId, UUID.randomUUID(),
                () -> self.transitionCompletedHandToResultDelayInternal(gameId, expectedHandNumber));
    }

    public Game transitionCompletedHandToResultDelayInternal(UUID gameId, int expectedHandNumber) {
        Game game = findGameById(gameId);
        if (!isExpectedLifecycleState(game, expectedHandNumber, HandLifecycleState.HAND_COMPLETED)) {
            logger.debug("Ignoring stale RESULT_DELAY transition for game {} hand {}", gameId, expectedHandNumber);
            return game;
        }

        game.setHandLifecycleState(HandLifecycleState.RESULT_DELAY);
        Game saved = gameStateService.persistFull(game);
        notificationService.broadcastGameUpdate(saved);
        logger.info("Game {} hand {} entered RESULT_DELAY", gameId, expectedHandNumber);
        return saved;
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Optional<Game> startNextHandFromLifecycle(UUID gameId, int expectedHandNumber) {
        if (!appProperties.getGame().isSingleWriterEnabled()) {
            return startNextHandFromLifecycleInternal(gameId, expectedHandNumber);
        }
        return commandDispatcher.submit(gameId, UUID.randomUUID(),
                () -> self.startNextHandFromLifecycleInternal(gameId, expectedHandNumber));
    }

    public Optional<Game> startNextHandFromLifecycleInternal(UUID gameId, int expectedHandNumber) {
        Game game = findGameById(gameId);
        if (!isExpectedLifecycleState(game, expectedHandNumber, HandLifecycleState.RESULT_DELAY)) {
            logger.debug("Ignoring stale NEXT_HAND transition for game {} hand {}", gameId, expectedHandNumber);
            return Optional.of(game);
        }

        long playersWithChips = game.getPlayers().stream()
                .filter(player -> player.getChips() > 0)
                .count();
        if (playersWithChips < 2) {
            logger.info("Game {} remains completed after hand {} - not enough players for next hand",
                    gameId, expectedHandNumber);
            return Optional.empty();
        }

        game.setHandLifecycleState(HandLifecycleState.NEXT_HAND);
        Game pendingNextHand = gameStateService.persistFull(game);
        notificationService.broadcastGameUpdate(pendingNextHand);

        // Already serialized on this table's chain — call the core, never re-submit.
        return Optional.of(startNewHandInternal(gameId));
    }

    @CacheEvict(value = "games", key = "#gameId")
    public Game handleTurnTimeout(
            UUID gameId,
            UUID expectedPlayerId,
            String expectedPhase,
            int expectedCurrentBet,
            int expectedCommunityCardCount) {
        if (!appProperties.getGame().isSingleWriterEnabled()) {
            return handleTurnTimeoutInternal(gameId, expectedPlayerId, expectedPhase,
                    expectedCurrentBet, expectedCommunityCardCount);
        }
        return commandDispatcher.submit(gameId, UUID.randomUUID(),
                () -> self.handleTurnTimeoutInternal(gameId, expectedPlayerId, expectedPhase,
                        expectedCurrentBet, expectedCommunityCardCount));
    }

    public Game handleTurnTimeoutInternal(
            UUID gameId,
            UUID expectedPlayerId,
            String expectedPhase,
            int expectedCurrentBet,
            int expectedCommunityCardCount) {
        Game game = findGameById(gameId);
        Player currentPlayer = game.getCurrentPlayer();

        if (game.isFinished()
                || currentPlayer == null
                || !currentPlayer.getId().equals(expectedPlayerId)
                || !game.getPhase().name().equals(expectedPhase)
                || game.getCurrentBet() != expectedCurrentBet
                || game.getCommunityCards().size() != expectedCommunityCardCount
                || currentPlayer.isBot()
                || !currentPlayer.canAct()) {
            logger.debug("Ignoring stale turn timeout for game {} player {}", gameId, expectedPlayerId);
            return game;
        }

        PlayerAction timeoutAction = currentPlayer.getBetAmount() < game.getCurrentBet()
                ? PlayerAction.FOLD
                : PlayerAction.CHECK;

        logger.info("Turn timeout for player {} in game {} - auto {}",
                currentPlayer.getName(), gameId, timeoutAction);

        // Already on this table's command — call the core directly to avoid re-submitting (deadlock).
        return playerActInternal(gameId, expectedPlayerId, timeoutAction, 0);
    }

    private void validatePlayerCount(List<PlayerInfo> playersInfo) {
        if (playersInfo == null || playersInfo.size() < 2 || playersInfo.size() > 10) {
            throw new IllegalArgumentException("Player count must be between 2 and 10");
        }
    }

    private void validatePlayerTurn(Game game, UUID playerId) {
        Player currentPlayer = game.getCurrentPlayer();

        
        
        if (currentPlayer == null)
            return;

        if (!currentPlayer.getId().equals(playerId)) {
            
            if (playerCanLegallyActOutOfTurn(game, playerId)) {
                return;
            }
            throw new IllegalStateException("It is not this player's turn");
        }
    }

    private boolean playerCanLegallyActOutOfTurn(Game game, UUID playerId) {
        return game.getPlayers().stream()
                .filter(Player::canAct)
                .count() == 1;
    }

    private void validatePlayerCanAct(Player player) {
        if (player.isFolded()) {
            throw new IllegalStateException("Player has already folded");
        }
        if (player.isAllIn()) {
            throw new IllegalStateException("Player is all-in and cannot act");
        }
    }

    private void dealHoleCards(Game game, Deck deck) {

        for (int round = 0; round < 2; round++) {
            for (Player player : game.getPlayers()) {
                if (player.getChips() > 0) {
                    player.addCardToHand(deck.drawCard());
                }
            }
        }
    }

    private void postBlinds(Game game) {
        List<Player> activePlayers = getActivePlayersInOrder(game);
        if (activePlayers.size() < 2)
            return;

        int dealerPos = game.getDealerPosition() % activePlayers.size();

        int sbPos = (dealerPos + 1) % activePlayers.size();

        int bbPos = (dealerPos + 2) % activePlayers.size();

        if (activePlayers.size() == 2) {
            sbPos = dealerPos;
            bbPos = (dealerPos + 1) % 2;
        }

        Player sbPlayer = activePlayers.get(sbPos);
        Player bbPlayer = activePlayers.get(bbPos);

        // Collect missed blinds from players who owe them (official poker rules)
        collectMissedBlinds(game, activePlayers, sbPlayer, bbPlayer);

        // Post small blind - player posts what they can (may be all-in)
        int sbAmount = sbPlayer.placeBet(game.getSmallBlind());
        game.setCurrentPot(game.getCurrentPot() + sbAmount);
        if (sbPlayer.getChips() == 0) {
            logger.info("Player {} is all-in posting small blind ({}/{})",
                    sbPlayer.getName(), sbAmount, game.getSmallBlind());
        }

        // Post big blind - player posts what they can (may be all-in)
        int bbAmount = bbPlayer.placeBet(game.getBigBlind());
        game.setCurrentPot(game.getCurrentPot() + bbAmount);
        if (bbPlayer.getChips() == 0) {
            logger.info("Player {} is all-in posting big blind ({}/{})",
                    bbPlayer.getName(), bbAmount, game.getBigBlind());
        }

        // Current bet should be the actual amount the BB posted (for short stack scenarios)
        // But minimum call should still be big blind for players who can afford it
        int actualCurrentBet = Math.max(sbPlayer.getBetAmount(), bbPlayer.getBetAmount());
        game.setCurrentBet(Math.max(actualCurrentBet, game.getBigBlind()));
        game.setLastRaiseAmount(game.getBigBlind());
        game.setMinRaiseAmount(game.getBigBlind());

        int firstToActPos = (bbPos + 1) % activePlayers.size();
        if (activePlayers.size() == 2) {
            firstToActPos = sbPos;
        }

        Player firstToAct = activePlayers.get(firstToActPos);
        game.setCurrentPlayerIndex(game.getPlayers().indexOf(firstToAct));

        logger.debug("Blinds posted: {} (SB: {}), {} (BB: {})",
                sbPlayer.getName(), sbAmount, bbPlayer.getName(), bbAmount);
    }

    /**
     * Collects missed blinds from players who owe them according to official poker rules.
     * - Dead small blind: goes directly to pot (doesn't count as bet)
     * - Big blind: posted as live blind (counts as bet for calling purposes)
     * Players who were sitting out must post missed blinds to re-enter play.
     */
    private void collectMissedBlinds(Game game, List<Player> activePlayers,
                                      Player sbPlayer, Player bbPlayer) {
        for (Player player : activePlayers) {
            // Skip the current blind posters - they're paying regular blinds
            if (player.equals(sbPlayer) || player.equals(bbPlayer)) {
                continue;
            }

            int missedAmount = game.getMissedBlindAmount(player.getSeatPosition());
            if (missedAmount > 0) {
                // Player owes missed blinds
                int actualPosted = Math.min(missedAmount, player.getChips());
                if (actualPosted > 0) {
                    // Missed blinds go to pot as "dead money" (don't count toward player's bet)
                    player.setChips(player.getChips() - actualPosted);
                    game.setCurrentPot(game.getCurrentPot() + actualPosted);

                    logger.info("Player {} posted {} missed blind (dead money to pot)",
                            player.getName(), actualPosted);

                    // Clear the missed blind after posting
                    game.clearMissedBlind(player.getSeatPosition());

                    if (player.getChips() == 0) {
                        player.setAllIn(true);
                        logger.info("Player {} is all-in after posting missed blinds", player.getName());
                    }
                }
            }
        }
    }

    private List<Player> getActivePlayersInOrder(Game game) {
        return game.getPlayers().stream()
                .filter(p -> !p.isFolded() && p.getChips() >= 0)
                .toList();
    }

    private void handleFold(Game game, Player player) {
        player.setFolded(true);
        logger.debug("Player {} folded", player.getName());
    }

    private void handleCheck(Game game, Player player) {
        if (player.getBetAmount() < game.getCurrentBet()) {
            throw new IllegalStateException("Cannot check when facing a bet. Must call, raise, or fold.");
        }
        logger.debug("Player {} checked", player.getName());
    }

    private int handleCall(Game game, Player player) {
        int callAmount = game.getCurrentBet() - player.getBetAmount();
        if (callAmount <= 0) {

            return 0;
        }

        int actualCall = player.call(game.getCurrentBet());
        game.setCurrentPot(game.getCurrentPot() + actualCall);

        logger.debug("Player {} called {} (total bet: {})",
                player.getName(), actualCall, player.getBetAmount());
        return actualCall;
    }

    private int handleBet(Game game, Player player, int amount) {
        if (game.getCurrentBet() > 0) {
            throw new IllegalStateException("Cannot bet when there's already a bet. Use raise.");
        }

        if (amount < game.getBigBlind()) {
            throw new IllegalArgumentException("Bet must be at least the big blind");
        }

        int actualBet = player.placeBet(amount);
        game.setCurrentPot(game.getCurrentPot() + actualBet);
        game.setCurrentBet(actualBet);
        game.setLastRaiseAmount(actualBet);
        game.setMinRaiseAmount(actualBet);
        game.setLastAggressorId(player.getId()); // Track last aggressor for showdown order

        resetActedFlags(game, player);
        logger.debug("Player {} bet {}", player.getName(), actualBet);
        return actualBet;
    }

    private int handleRaise(Game game, Player player, int totalAmount) {
        int toCall = game.getCurrentBet() - player.getBetAmount();
        int playerTotalAvailable = player.getChips() + player.getBetAmount();

        // If player can't even call, they must fold or go all-in
        if (player.getChips() <= 0) {
            logger.warn("Player {} has no chips to raise, treating as check/call", player.getName());
            return 0;
        }

        // If total amount requested exceeds what player has, go all-in
        if (totalAmount >= playerTotalAvailable) {
            logger.info("Player {} going all-in with {} chips", player.getName(), player.getChips());
            int allInAmount = player.getChips();
            int actualBet = player.placeBet(allInAmount);
            game.setCurrentPot(game.getCurrentPot() + actualBet);

            // Only update current bet if it's higher than existing
            int newTotalBet = player.getBetAmount();
            if (newTotalBet > game.getCurrentBet()) {
                int raiseAmount = newTotalBet - game.getCurrentBet();
                game.setCurrentBet(newTotalBet);
                game.setLastRaiseAmount(raiseAmount);
                game.setLastAggressorId(player.getId()); // Track last aggressor
                if (raiseAmount >= game.getMinRaiseAmount()) {
                    game.setMinRaiseAmount(raiseAmount);
                }
                resetActedFlags(game, player);
            }
            return actualBet;
        }

        int raiseAmount = totalAmount - game.getCurrentBet();

        // If raise is less than minimum but player has more chips, it's an error
        // But if player doesn't have enough for min raise, allow all-in
        int minRaiseTotal = game.getCurrentBet() + game.getMinRaiseAmount();
        if (totalAmount < minRaiseTotal && playerTotalAvailable >= minRaiseTotal) {
            throw new IllegalArgumentException(
                    "Raise must be at least " + game.getMinRaiseAmount() + " more than current bet");
        }

        // If player can't afford min raise, go all-in
        if (totalAmount < minRaiseTotal) {
            logger.info("Player {} can't afford min raise, going all-in with {}", player.getName(), player.getChips());
            int allInAmount = player.getChips();
            int actualBet = player.placeBet(allInAmount);
            game.setCurrentPot(game.getCurrentPot() + actualBet);

            int newTotalBet = player.getBetAmount();
            if (newTotalBet > game.getCurrentBet()) {
                int actualRaise = newTotalBet - game.getCurrentBet();
                game.setCurrentBet(newTotalBet);
                game.setLastRaiseAmount(actualRaise);
                game.setLastAggressorId(player.getId()); // Track last aggressor
                resetActedFlags(game, player);
            }
            return actualBet;
        }

        int totalToAdd = toCall + raiseAmount;
        int actualBet = player.placeBet(totalToAdd);
        game.setCurrentPot(game.getCurrentPot() + actualBet);
        game.setCurrentBet(player.getBetAmount());
        game.setLastRaiseAmount(raiseAmount);
        game.setMinRaiseAmount(raiseAmount);
        game.setLastAggressorId(player.getId()); // Track last aggressor for showdown order

        resetActedFlags(game, player);
        logger.debug("Player {} raised to {}", player.getName(), player.getBetAmount());
        return actualBet;
    }

    private int handleAllIn(Game game, Player player) {
        int allInAmount = player.getChips();

        if (allInAmount <= 0) {
            logger.warn("Player {} tried to go all-in with no chips", player.getName());
            return 0;
        }

        logger.info("Player {} going ALL-IN with {} chips", player.getName(), allInAmount);

        int actualBet = player.placeBet(allInAmount);
        game.setCurrentPot(game.getCurrentPot() + actualBet);

        int playerTotalBet = player.getBetAmount();

        // If this all-in raises the current bet, update game state
        if (playerTotalBet > game.getCurrentBet()) {
            int raiseAmount = playerTotalBet - game.getCurrentBet();
            game.setCurrentBet(playerTotalBet);
            game.setLastRaiseAmount(raiseAmount);
            game.setLastAggressorId(player.getId()); // Track last aggressor for showdown order

            // Only update min raise if it's a valid raise amount
            if (raiseAmount >= game.getMinRaiseAmount()) {
                game.setMinRaiseAmount(raiseAmount);
            }

            // Reset acted flags so other players must respond
            resetActedFlags(game, player);
        }

        return actualBet;
    }

    private void resetActedFlags(Game game, Player excludePlayer) {
        for (Player p : game.getPlayers()) {
            if (!p.getId().equals(excludePlayer.getId()) && !p.isFolded() && !p.isAllIn()) {
                p.setHasActed(false);
            }
        }
    }

    private void advanceGame(Game game) {

        List<Player> playersInHand = game.getPlayersStillInHand();
        if (playersInHand.isEmpty()) {
            logger.warn("No players in hand - cannot advance game");
            return;
        }
        if (playersInHand.size() == 1) {

            awardPotToSingleWinner(game, playersInHand.get(0));
            return;
        }

        if (isBettingRoundComplete(game)) {
            advanceToNextPhase(game);
        } else {
            advanceToNextPlayer(game);
        }
    }

    private boolean isBettingRoundComplete(Game game) {
        List<Player> activePlayers = game.getPlayers().stream()
                .filter(p -> !p.isFolded())
                .toList();

        for (Player player : activePlayers) {
            if (!player.isAllIn()) {
                if (!player.hasActed()) {
                    return false;
                }
                if (player.getBetAmount() < game.getCurrentBet()) {
                    return false;
                }
            }
        }

        return true;
    }

    private void advanceToNextPlayer(Game game) {
        List<Player> players = game.getPlayers();
        int currentIndex = game.getCurrentPlayerIndex();
        int playersCount = players.size();

        if (playersCount == 0) {
            logger.warn("Cannot advance to next player - no players in game");
            return;
        }

        for (int i = 1; i <= playersCount; i++) {
            int nextIndex = (currentIndex + i) % playersCount;
            Player nextPlayer = players.get(nextIndex);

            if (nextPlayer.canAct()) {
                game.setCurrentPlayerIndex(nextIndex);
                return;
            }
        }

        logger.warn("No active players found to advance to");
    }

    private void advanceToNextPhase(Game game) {

        for (Player player : game.getPlayers()) {
            player.resetBetForNewRound();
        }
        game.setCurrentBet(0);
        game.setMinRaiseAmount(game.getBigBlind());

        setFirstPlayerAfterDealer(game);

        switch (game.getPhase()) {
            case PRE_FLOP -> {
                game.setPhase(GamePhase.FLOP);
                dealCommunityCards(game, 3);
                logger.info("Dealing FLOP");
            }
            case FLOP -> {
                game.setPhase(GamePhase.TURN);
                dealCommunityCards(game, 1);
                logger.info("Dealing TURN");
            }
            case TURN -> {
                game.setPhase(GamePhase.RIVER);
                dealCommunityCards(game, 1);
                logger.info("Dealing RIVER");
            }
            case RIVER -> {
                game.setPhase(GamePhase.SHOWDOWN);
                resolveShowdown(game);
            }
            default -> logger.warn("Unexpected phase: {}", game.getPhase());
        }

        if (game.getPhase() != GamePhase.SHOWDOWN) {
            checkForAutoAdvance(game);
        }
    }

    private void setFirstPlayerAfterDealer(Game game) {
        List<Player> players = game.getPlayers();
        int dealerPos = game.getDealerPosition();

        for (int i = 1; i <= players.size(); i++) {
            int pos = (dealerPos + i) % players.size();
            Player player = players.get(pos);
            if (player.canAct()) {
                game.setCurrentPlayerIndex(pos);
                return;
            }
        }
    }

    private void checkForAutoAdvance(Game game) {

        List<Player> canAct = game.getPlayers().stream()
                .filter(Player::canAct)
                .toList();

        if (canAct.size() <= 1) {

            while (game.getPhase() != GamePhase.SHOWDOWN) {
                autoAdvancePhase(game);
            }
        }
    }

    private void autoAdvancePhase(Game game) {
        switch (game.getPhase()) {
            case PRE_FLOP -> {
                game.setPhase(GamePhase.FLOP);
                dealCommunityCards(game, 3);
            }
            case FLOP -> {
                game.setPhase(GamePhase.TURN);
                dealCommunityCards(game, 1);
            }
            case TURN -> {
                game.setPhase(GamePhase.RIVER);
                dealCommunityCards(game, 1);
            }
            case RIVER -> {
                game.setPhase(GamePhase.SHOWDOWN);
                resolveShowdown(game);
            }
        }
    }

    private void dealCommunityCards(Game game, int count) {

        if (!game.getDeck().isEmpty()) {
            game.getDeck().remove(0);
        }

        for (int i = 0; i < count && !game.getDeck().isEmpty(); i++) {
            game.addCommunityCard(game.getDeck().remove(0));
        }
    }

    public ShowdownResult resolveShowdown(Game game) {
        logger.info("Resolving showdown for game {}", game.getId());

        List<Player> playersInHand = game.getPlayersStillInHand();
        List<WinnerInfo> allWinners = new ArrayList<>();

        // Order players for showdown according to official rules:
        // 1. Last aggressor shows first
        // 2. If no aggression, first active player after button shows first
        List<Player> showdownOrder = getShowdownOrder(game, playersInHand);
        logger.info("Showdown order: {}", showdownOrder.stream()
                .map(Player::getName)
                .collect(Collectors.joining(", ")));

        List<PotInfo> pots = calculatePots(game);

        for (PotInfo pot : pots) {
            List<Player> eligiblePlayers = playersInHand.stream()
                    .filter(p -> pot.eligiblePlayerIds.contains(p.getId()))
                    .toList();

            if (eligiblePlayers.isEmpty())
                continue;

            Map<Player, HandRanking> rankings = evaluateHands(eligiblePlayers, game.getCommunityCards());
            List<Player> potWinners = findBestHands(rankings);

            if (potWinners.isEmpty()) {
                logger.warn("No winners found for pot - skipping distribution");
                continue;
            }

            int winAmount = pot.amount / potWinners.size();
            int remainder = pot.amount % potWinners.size();

            for (int i = 0; i < potWinners.size(); i++) {
                Player winner = potWinners.get(i);
                int amount = winAmount + (i == 0 ? remainder : 0);
                winner.addWinnings(amount);

                HandRanking ranking = rankings.get(winner);
                allWinners.add(new WinnerInfo(
                        winner.getId(),
                        winner.getName(),
                        amount,
                        ranking.getDescription(),
                        new ArrayList<>(winner.getHand())));

                playerStatisticsService.recordShowdown(winner.getName(), true);
                playerStatisticsService.recordWin(winner.getName(), amount);
                if (winner.isAllIn()) {
                    playerStatisticsService.recordAllInResult(winner.getName(), true);
                }

                logger.info("Player {} wins {} with {}",
                        winner.getName(), amount, ranking.getDescription());
            }

            for (Player loser : eligiblePlayers) {
                if (!potWinners.contains(loser)) {
                    playerStatisticsService.recordShowdown(loser.getName(), false);
                    if (loser.isAllIn()) {
                        playerStatisticsService.recordAllInResult(loser.getName(), false);
                    }
                }
            }
        }

        game.setFinished(true);
        game.setHandLifecycleState(HandLifecycleState.HAND_COMPLETED);
        game.setCurrentPot(0);

        if (!allWinners.isEmpty()) {

            WinnerInfo mainWinner = allWinners.stream()
                    .max(Comparator.comparingInt(WinnerInfo::getAmountWon))
                    .orElse(allWinners.get(0));

            game.setWinnerName(mainWinner.getPlayerName());
            game.setWinningHandDescription(mainWinner.getHandDescription());
            game.getWinnerIds().clear();
            allWinners.forEach(w -> game.getWinnerIds().add(w.getPlayerId()));
        }

        int totalWon = allWinners.stream().mapToInt(WinnerInfo::getAmountWon).sum();
        String message = buildWinMessage(allWinners);

        ShowdownResult result = new ShowdownResult(allWinners, totalWon, message);

        if (!allWinners.isEmpty()) {
            WinnerInfo mainWinner = allWinners.get(0);
            handHistoryService.recordCommunityCards(game.getId(), game.getCommunityCards());
            handHistoryService.finishRecording(game.getId(),
                    mainWinner.getPlayerName(),
                    mainWinner.getHandDescription(),
                    totalWon);
        }

        notificationService.broadcastShowdown(game, result);

        return result;
    }

    /**
     * Determines the showdown order according to official poker rules:
     * 1. If there was aggression (bet/raise) in the final betting round, the last aggressor shows first
     * 2. If no aggression (all checks or all-in situations), first active player after button shows first
     * 3. Subsequent players show in clockwise order from the first player
     */
    private List<Player> getShowdownOrder(Game game, List<Player> playersInHand) {
        List<Player> orderedPlayers = new ArrayList<>();

        if (playersInHand.isEmpty()) {
            return orderedPlayers;
        }

        // Find starting position for showdown
        int startIndex;
        Player lastAggressor = game.getLastAggressor();

        if (lastAggressor != null && playersInHand.contains(lastAggressor)) {
            // Last aggressor shows first
            startIndex = playersInHand.indexOf(lastAggressor);
            logger.debug("Last aggressor {} shows first", lastAggressor.getName());
        } else {
            // No aggression - first active player after dealer shows first
            int dealerPos = game.getDealerPosition();
            List<Player> allPlayers = game.getPlayers();

            // Find first player in hand after dealer
            startIndex = 0;
            for (int i = 1; i <= allPlayers.size(); i++) {
                int checkPos = (dealerPos + i) % allPlayers.size();
                Player checkPlayer = allPlayers.get(checkPos);
                if (playersInHand.contains(checkPlayer)) {
                    startIndex = playersInHand.indexOf(checkPlayer);
                    logger.debug("No aggressor - {} (first after dealer) shows first", checkPlayer.getName());
                    break;
                }
            }
        }

        // Build ordered list starting from startIndex
        for (int i = 0; i < playersInHand.size(); i++) {
            int index = (startIndex + i) % playersInHand.size();
            orderedPlayers.add(playersInHand.get(index));
        }

        return orderedPlayers;
    }

    private void awardPotToSingleWinner(Game game, Player winner) {
        int potAmount = game.getCurrentPot();
        winner.addWinnings(potAmount);

        game.setWinnerName(winner.getName());
        game.setWinningHandDescription("All opponents folded");
        game.getWinnerIds().clear();
        game.getWinnerIds().add(winner.getId());
        game.setCurrentPot(0);
        game.setFinished(true);
        game.setPhase(GamePhase.SHOWDOWN);
        game.setHandLifecycleState(HandLifecycleState.HAND_COMPLETED);

        playerStatisticsService.recordWin(winner.getName(), potAmount);

        handHistoryService.finishRecording(game.getId(),
                winner.getName(),
                "All opponents folded",
                potAmount);

        logger.info("Player {} wins {} - all opponents folded", winner.getName(), potAmount);
    }

    private List<PotInfo> calculatePots(Game game) {
        // Side-pot math lives in the shared PotMath (single source of truth with the aggregate engine). Pot
        // amounts include folded players' dead money; eligibility is limited to players still in the hand.
        List<PotMath.Contribution> contributions = game.getPlayers().stream()
                .map(p -> new PotMath.Contribution(
                        p.getId(), p.getTotalBetInRound(), p.isFolded(), p.isAllIn()))
                .toList();
        return PotMath.calculate(contributions, game.getCurrentPot()).stream()
                .map(pot -> new PotInfo(pot.amount(), pot.eligiblePlayerIds()))
                .toList();
    }

    private Map<Player, HandRanking> evaluateHands(List<Player> players, List<Card> communityCards) {
        Map<Player, HandRanking> rankings = new HashMap<>();

        for (Player player : players) {
            HandRanking ranking = handEvaluator.evaluate(player.getHand(), communityCards);
            if (ranking != null) {
                rankings.put(player, ranking);
            }
        }

        return rankings;
    }

    private List<Player> findBestHands(Map<Player, HandRanking> rankings) {
        if (rankings.isEmpty())
            return List.of();

        HandRanking bestRanking = rankings.values().stream()
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (bestRanking == null)
            return List.of();

        return rankings.entrySet().stream()
                .filter(e -> e.getValue().compareTo(bestRanking) == 0)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String buildWinMessage(List<WinnerInfo> winners) {
        if (winners.isEmpty())
            return "No winner";

        if (winners.size() == 1) {
            WinnerInfo w = winners.get(0);
            return String.format("%s wins %d with %s",
                    w.getPlayerName(), w.getAmountWon(), w.getHandDescription());
        }

        return winners.stream()
                .map(w -> String.format("%s wins %d", w.getPlayerName(), w.getAmountWon()))
                .collect(Collectors.joining(", "));
    }

    private record PotInfo(int amount, List<UUID> eligiblePlayerIds) {
    }

    private boolean usesAggregateEngine() {
        return appProperties.getGame().getEngine() == GameEngine.AGGREGATE;
    }

    private Game createNewGameViaAggregate(
            List<PlayerInfo> playersInfo, Integer smallBlind, Integer bigBlind) {
        logger.info("Creating game via aggregate engine with {} players", playersInfo.size());

        int sb = resolveBlind(smallBlind, appProperties.getGame().getDefaultSmallBlind());
        int bb = resolveBlind(bigBlind, appProperties.getGame().getDefaultBigBlind());

        PokerGame aggregate = PokerGame.create(playersInfo, Chips.of(sb), Chips.of(bb));
        assignPlayerIdsFromInfo(aggregate, playersInfo);
        aggregate.startNewHand();

        // Do NOT pre-assign the id: Game.id is @GeneratedValue, so a manually-set id makes Spring Data's
        // save() treat the fresh entity as a detached merge (→ "uninitialized version") instead of an insert.
        // The id is generated on persist; subsequent actions reconstitute the aggregate from the Game entity,
        // so the aggregate id tracks the persisted game id from then on (mirrors CashGameService.openHand).
        Game game = new Game();
        game.setSmallBlind(sb);
        game.setBigBlind(bb);
        game.setMinRaiseAmount(bb);
        game.setLastRaiseAmount(bb);
        game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);
        pokerGameMapper.applyToGame(aggregate, game);

        for (PlayerInfo info : playersInfo) {
            playerStatisticsService.startSession(info.getName());
        }

        publishDomainEvents(aggregate);

        Game savedGame = gameStateService.persistFullSync(game);
        handHistoryService.startRecording(savedGame);
        notificationService.broadcastGameUpdate(savedGame);
        turnTimeoutService.scheduleForCurrentTurn(savedGame);

        metricsService.incrementGamesCreated();
        metricsService.setActivePlayers(playersInfo.size());
        logger.info("Created new game {} with {} players (aggregate engine)", savedGame.getId(), playersInfo.size());
        return savedGame;
    }

    private Game playerActViaAggregate(UUID gameId, UUID playerId, PlayerAction action, int amount) {
        Game game = findGameById(gameId);
        Player player = findPlayerInGame(game, playerId);

        validatePlayerTurn(game, playerId);
        validatePlayerCanAct(player);

        logger.debug("Player {} performing action {} with amount {} (aggregate engine)",
                player.getName(), action, amount);

        metricsService.recordPlayerAction(action.name());

        int chipsBefore = player.getChips();
        PokerGame aggregate = pokerGameMapper.fromGame(game);
        Chips betAmount = action == PlayerAction.BET || action == PlayerAction.RAISE
                ? Chips.of(amount)
                : null;

        aggregate.executeAction(playerId, action, betAmount);
        pokerGameMapper.applyToGame(aggregate, game);

        int actualAmount = chipsBefore - player.getChips();
        if (actualAmount < 0) {
            actualAmount = 0;
        }

        if (game.isFinished()) {
            finalizeAggregateHand(game, aggregate);
        }

        player.setHasActed(true);

        handHistoryService.recordAction(gameId, player, action, actualAmount, game.getPhase());

        // Statistics flow through domain events (StatisticsEventListener) on the aggregate path.
        // Publish after finalizeAggregateHand has consumed HandCompleted, before the buffer flush in
        // persistAfterAction so PlayerActed is buffered first.
        publishDomainEvents(aggregate);

        notificationService.broadcastPlayerAction(game, player, action.name(), actualAmount);

        Player nextPlayer = game.getCurrentPlayer();
        logger.info("After {} by {} (aggregate): next player is {} (isBot: {}, index: {})",
                action, player.getName(),
                nextPlayer != null ? nextPlayer.getName() : "null",
                nextPlayer != null ? nextPlayer.isBot() : "N/A",
                game.getCurrentPlayerIndex());

        return persistAfterAction(game);
    }

    private Game startNewHandViaAggregate(UUID gameId) {
        Game game = findGameById(gameId);
        game.getPlayers().removeIf(p -> p.getChips() <= 0);

        if (game.getPlayers().size() < 2) {
            throw new IllegalStateException("Not enough players with chips to continue");
        }

        PokerGame aggregate = pokerGameMapper.fromGame(game);
        aggregate.startNewHand();
        pokerGameMapper.applyToGame(aggregate, game);
        game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);

        publishDomainEvents(aggregate);

        logger.info("Started new hand {} in game {} (aggregate engine)", game.getHandNumber(), gameId);
        Game saved = gameStateService.persistFull(game);
        notificationService.broadcastGameUpdate(saved);
        turnTimeoutService.scheduleForCurrentTurn(saved);
        return saved;
    }

    private void finalizeAggregateHand(Game game, PokerGame aggregate) {
        game.setHandLifecycleState(HandLifecycleState.HAND_COMPLETED);

        HandCompleted completed = aggregate.getDomainEvents().stream()
                .filter(HandCompleted.class::isInstance)
                .map(HandCompleted.class::cast)
                .reduce((first, second) -> second)
                .orElse(null);

        if (completed == null || completed.getPotResults().isEmpty()) {
            return;
        }

        List<WinnerInfo> winners = new ArrayList<>();
        int totalWon = 0;
        for (HandCompleted.PotResult potResult : completed.getPotResults()) {
            int amount = potResult.amount().amount();
            totalWon += amount;
            // Win / showdown statistics are recorded by StatisticsEventListener from the
            // HandCompleted domain event (published by the caller), not imperatively here.

            Player winner = findPlayerInGame(game, potResult.winnerId());
            winners.add(new WinnerInfo(
                    potResult.winnerId(),
                    potResult.winnerName(),
                    amount,
                    potResult.handDescription(),
                    new ArrayList<>(winner.getHand())));
        }

        WinnerInfo mainWinner = winners.stream()
                .max(Comparator.comparingInt(WinnerInfo::getAmountWon))
                .orElse(winners.get(0));

        handHistoryService.recordCommunityCards(game.getId(), game.getCommunityCards());
        handHistoryService.finishRecording(
                game.getId(),
                mainWinner.getPlayerName(),
                mainWinner.getHandDescription() != null ? mainWinner.getHandDescription() : "Winner",
                totalWon);

        if (completed.wentToShowdown()) {
            ShowdownResult result = new ShowdownResult(winners, totalWon, buildWinMessage(winners));
            notificationService.broadcastShowdown(game, result);
        }
    }

    private static int resolveBlind(Integer override, int defaultBlind) {
        return override != null && override > 0 ? override : defaultBlind;
    }

    private static void assignPlayerIdsFromInfo(PokerGame aggregate, List<PlayerInfo> playersInfo) {
        List<Player> players = aggregate.getPlayers();
        for (int i = 0; i < playersInfo.size() && i < players.size(); i++) {
            PlayerInfo info = playersInfo.get(i);
            if (info.getPlayerId() != null) {
                if (!info.isBot()) {
                    players.get(i).setUserId(info.getPlayerId());
                }
                if (info.isUseStableId()) {
                    players.get(i).setId(info.getPlayerId());
                }
            }
        }
    }

    private Game findGameById(UUID gameId) {
        return gameStateService.load(gameId);
    }

    private Game persistAfterAction(Game game) {
        if (game.isFinished()) {
            turnTimeoutService.cancel(game.getId());
            playerStatisticsService.flushBufferedActionsForGame(game);
            Game saved = gameStateService.persistFull(game);
            syncTournamentChipsAfterHand(saved);
            handLifecycleService.scheduleAfterHandCompleted(saved);
            return saved;
        }
        Game saved = gameStateService.afterPlayerAction(game);
        // Skip the live turn timer when a pyramid round is driven synchronously on this thread.
        if (!HandLifecycleScheduling.isSuppressed()) {
            turnTimeoutService.scheduleForCurrentTurn(saved);
        }
        return saved;
    }

    private boolean isExpectedLifecycleState(
            Game game,
            int expectedHandNumber,
            HandLifecycleState expectedState) {
        return game.isFinished()
                && game.getHandNumber() == expectedHandNumber
                && game.getHandLifecycleState() == expectedState;
    }

    private void syncTournamentChipsAfterHand(Game game) {
        if (game.getId() == null) {
            return;
        }
        tournamentTableShardService.findTableForGame(game.getId())
                .ifPresent(table -> tournamentChipSyncService.syncAfterHand(
                        table.getTournament().getId(), game));
    }

    private Player findPlayerInGame(Game game, UUID playerId) {
        return game.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Player not found: " + playerId));
    }
}
