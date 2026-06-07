package com.truholdem.domain.aggregate;

import com.truholdem.domain.event.*;
import com.truholdem.domain.exception.GameStateException;
import com.truholdem.domain.exception.InvalidActionException;
import com.truholdem.domain.exception.PlayerNotFoundException;
import com.truholdem.domain.value.BettingRound;
import com.truholdem.domain.value.Chips;
import com.truholdem.domain.value.HandRanker;
import com.truholdem.domain.value.Pot;
import com.truholdem.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


public class PokerGame {

    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 10;

    private final UUID id;

    private Long version;

    private final Instant createdAt;

    private Instant updatedAt;

    
    private final int smallBlindAmount;
    private final int bigBlindAmount;

    
    private GamePhase phase;

    private int dealerPosition;
    private int currentPlayerIndex;
    private int handNumber;
    private boolean finished;

    
    private int currentBet;
    private int minRaise;
    private int lastRaiseAmount;
    private int actionsThisRound;
    private UUID lastAggressorId;

    
    private int buttonSeatPosition;
    private boolean deadButton;
    private final Map<Integer, Integer> missedBlinds = new HashMap<>();

    
    private int potAmount;

    
    private final List<Player> players = new ArrayList<>();

    
    private final List<Card> communityCards = new ArrayList<>();

    
    private final List<Card> deck = new ArrayList<>();

    /**
     * Test seam (null in production): when set, the next deal uses this exact card order instead of shuffling,
     * so showdown / side-pot / split outcomes can be pinned deterministically. Package-private — only the
     * aggregate's own tests set it via {@link #useFixedDeck(List)}.
     */
    private List<Card> fixedDeck;


    private final List<Integer> sidePotAmounts = new ArrayList<>();

    
    private String winnerName;
    private String winningHandDescription;
    private final List<UUID> winnerIds = new ArrayList<>();

    
    private Instant handStartTime;

    
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    
    
    

    private PokerGame(UUID id, Chips smallBlind, Chips bigBlind) {
        this(id, smallBlind, bigBlind, Instant.now());
    }

    private PokerGame(UUID id, Chips smallBlind, Chips bigBlind, Instant createdAt) {
        this.id = id;
        this.smallBlindAmount = smallBlind.amount();
        this.bigBlindAmount = bigBlind.amount();
        this.phase = GamePhase.PRE_FLOP;
        this.dealerPosition = 0;
        this.currentPlayerIndex = 0;
        this.handNumber = 0;
        this.finished = false;
        this.currentBet = 0;
        this.minRaise = bigBlind.amount();
        this.lastRaiseAmount = bigBlind.amount();
        this.actionsThisRound = 0;
        this.buttonSeatPosition = 0;
        this.deadButton = false;
        this.potAmount = 0;
        this.createdAt = createdAt;
        this.updatedAt = Instant.now();
    }

    /**
     * Rebuilds an in-memory aggregate from a persisted {@link com.truholdem.model.Game}
     * snapshot. Player instances are shared by reference so mutations stay visible on
     * the entity the service will save.
     */
    public static PokerGame reconstitute(PersistedGameState state) {
        Objects.requireNonNull(state, "Persisted game state cannot be null");
        Objects.requireNonNull(state.id(), "Game id cannot be null");
        Objects.requireNonNull(state.players(), "Players cannot be null");

        Instant createdAt = state.createdAt() != null ? state.createdAt() : Instant.now();
        PokerGame game = new PokerGame(
                state.id(),
                Chips.of(state.smallBlindAmount()),
                Chips.of(state.bigBlindAmount()),
                createdAt);

        game.version = state.version();
        game.updatedAt = state.updatedAt() != null ? state.updatedAt() : Instant.now();
        game.phase = state.phase() != null ? state.phase() : GamePhase.PRE_FLOP;
        game.dealerPosition = state.dealerPosition();
        game.currentPlayerIndex = state.currentPlayerIndex();
        game.handNumber = state.handNumber();
        game.finished = state.finished();
        game.currentBet = state.currentBet();
        game.minRaise = state.minRaise();
        game.lastRaiseAmount = state.lastRaiseAmount();
        game.actionsThisRound = state.actionsThisRound();
        game.lastAggressorId = state.lastAggressorId();
        game.buttonSeatPosition = state.buttonSeatPosition();
        game.deadButton = state.deadButton();
        game.missedBlinds.clear();
        if (state.missedBlinds() != null) {
            game.missedBlinds.putAll(state.missedBlinds());
        }
        game.potAmount = state.potAmount();
        game.winnerName = state.winnerName();
        game.winningHandDescription = state.winningHandDescription();
        game.winnerIds.clear();
        if (state.winnerIds() != null) {
            game.winnerIds.addAll(state.winnerIds());
        }

        game.players.clear();
        game.players.addAll(state.players());

        game.communityCards.clear();
        if (state.communityCards() != null) {
            game.communityCards.addAll(state.communityCards());
        }

        game.deck.clear();
        if (state.deck() != null) {
            game.deck.addAll(state.deck());
        }

        game.domainEvents.clear();
        return game;
    }

    public PersistedGameState captureState() {
        return new PersistedGameState(
                id,
                version,
                createdAt,
                updatedAt,
                smallBlindAmount,
                bigBlindAmount,
                phase,
                dealerPosition,
                currentPlayerIndex,
                handNumber,
                finished,
                currentBet,
                minRaise,
                lastRaiseAmount,
                actionsThisRound,
                lastAggressorId,
                buttonSeatPosition,
                deadButton,
                Map.copyOf(missedBlinds),
                potAmount,
                List.copyOf(players),
                List.copyOf(communityCards),
                List.copyOf(deck),
                winnerName,
                winningHandDescription,
                List.copyOf(winnerIds));
    }

    
    
    

    
    public static PokerGame create(List<PlayerInfo> playerInfos, Chips smallBlind, Chips bigBlind) {
        Objects.requireNonNull(playerInfos, "Player infos cannot be null");
        Objects.requireNonNull(smallBlind, "Small blind cannot be null");
        Objects.requireNonNull(bigBlind, "Big blind cannot be null");

        if (playerInfos.size() < MIN_PLAYERS || playerInfos.size() > MAX_PLAYERS) {
            throw GameStateException.invalidPlayerCount(playerInfos.size(), MIN_PLAYERS, MAX_PLAYERS);
        }

        if (bigBlind.isLessThan(smallBlind)) {
            throw GameStateException.invalidBlinds(smallBlind.amount(), bigBlind.amount());
        }

        PokerGame game = new PokerGame(UUID.randomUUID(), smallBlind, bigBlind);

        
        List<UUID> playerIds = new ArrayList<>();
        int seatPosition = 0;
        for (PlayerInfo info : playerInfos) {
            Player player = new Player(info.getName(), info.getStartingChips(), info.isBot());
            player.setSeatPosition(seatPosition++);
            game.players.add(player);
            playerIds.add(player.getId());
        }

        
        game.initializeDeck();

        
        game.raiseEvent(new GameCreated(
                game.id,
                playerIds,
                Chips.of(playerInfos.get(0).getStartingChips()),
                smallBlind,
                bigBlind
        ));

        return game;
    }

    
    
    

    
    public void startNewHand() {
        if (finished) {
            throw GameStateException.gameAlreadyFinished(id);
        }

        List<Player> activePlayers = getPlayersWithChips();
        if (activePlayers.size() < MIN_PLAYERS) {
            throw GameStateException.notEnoughPlayers(id, activePlayers.size(), MIN_PLAYERS);
        }

        
        handNumber++;
        handStartTime = Instant.now();
        phase = GamePhase.PRE_FLOP;
        communityCards.clear();
        potAmount = 0;
        sidePotAmounts.clear();
        currentBet = 0;
        minRaise = bigBlindAmount;
        lastRaiseAmount = bigBlindAmount;
        actionsThisRound = 0;
        lastAggressorId = null;
        winnerName = null;
        winningHandDescription = null;
        winnerIds.clear();

        
        for (Player player : players) {
            player.clearHand();
            player.setFolded(false);
            player.setBetAmount(0);
            player.setHasActed(false);
            player.setAllIn(false);
            player.setTotalBetInRound(0);
        }

        
        if (handNumber > 1) {
            advanceButtonPosition();
        }

        
        initializeDeck();
        shuffleDeck();

        
        dealHoleCards();

        
        int sbIndex;
        int bbIndex;
        int firstToActIndex;
        if (getPlayersWithChips().size() == 2) {
            
            sbIndex = dealerPosition;
            bbIndex = findNextActivePlayerIndex(dealerPosition);
            firstToActIndex = sbIndex;
        } else {
            sbIndex = findNextActivePlayerIndex(dealerPosition);
            bbIndex = findNextActivePlayerIndex(sbIndex);
            firstToActIndex = findNextActivePlayerIndex(bbIndex);
        }

        Player sbPlayer = players.get(sbIndex);
        Player bbPlayer = players.get(bbIndex);

        
        collectMissedBlinds(sbPlayer, bbPlayer);

        postBlind(sbPlayer, smallBlindAmount, PlayerActed.ActionType.POST_SMALL_BLIND);
        postBlind(bbPlayer, bigBlindAmount, PlayerActed.ActionType.POST_BIG_BLIND);

        
        int actualCurrentBet = Math.max(sbPlayer.getBetAmount(), bbPlayer.getBetAmount());
        currentBet = Math.max(actualCurrentBet, bigBlindAmount);
        lastRaiseAmount = bigBlindAmount;
        minRaise = bigBlindAmount;

        currentPlayerIndex = firstToActIndex;

        
        raiseEvent(new GameStarted(id, dealerPosition, sbPlayer.getId(), bbPlayer.getId(), handNumber));
        
        updatedAt = Instant.now();
    }

    
    public void executeAction(UUID playerId, PlayerAction action, Chips amount) {
        Objects.requireNonNull(playerId, "Player ID cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");

        if (finished) {
            throw GameStateException.gameAlreadyFinished(id);
        }

        Player player = findPlayerById(playerId);
        Player currentPlayer = getCurrentPlayer();

        if (currentPlayer == null) {
            throw PlayerNotFoundException.noCurrentPlayer(id);
        }

        if (!player.getId().equals(currentPlayer.getId())) {
            throw InvalidActionException.notPlayersTurn(playerId, currentPlayer.getId());
        }

        if (player.isFolded()) {
            throw InvalidActionException.playerAlreadyFolded(playerId);
        }

        if (player.isAllIn()) {
            throw InvalidActionException.playerIsAllIn(playerId);
        }

        
        switch (action) {
            case FOLD -> executeFold(player);
            case CHECK -> executeCheck(player);
            case CALL -> executeCall(player);
            case BET -> executeBet(player, amount);
            case RAISE -> executeRaise(player, amount);
            case ALL_IN -> executeAllIn(player);
        }

        player.setHasActed(true);
        actionsThisRound++;

        
        raiseEvent(new PlayerActed(
                id, playerId, player.getName(),
                toEventActionType(action),
                amount != null ? amount : Chips.zero(),
                phase,
                Chips.of(potAmount),
                Chips.of(player.getChips()),
                player.isAllIn()
        ));

        
        if (isBettingRoundComplete()) {
            advancePhase();
        } else {
            advanceToNextPlayer();
        }

        updatedAt = Instant.now();
    }

    
    
    

    private void executeFold(Player player) {
        player.fold();
        
        
        List<Player> activePlayers = getActivePlayers();
        if (activePlayers.size() == 1) {
            awardPotToLastPlayer(activePlayers.get(0));
        }
    }

    private void executeCheck(Player player) {
        int amountToCall = currentBet - player.getBetAmount();
        if (amountToCall > 0) {
            throw InvalidActionException.cannotCheckFacingBet(
                    player.getId(), Chips.of(currentBet), Chips.of(player.getBetAmount()));
        }
    }

    private void executeCall(Player player) {
        int amountToCall = currentBet - player.getBetAmount();
        if (amountToCall <= 0) {
            return; 
        }

        int actualBet = Math.min(amountToCall, player.getChips());
        addToPot(player, actualBet);
    }

    private void executeBet(Player player, Chips amount) {
        if (amount == null || amount.isZero()) {
            throw InvalidActionException.invalidBetAmount(player.getId(), 0);
        }

        if (currentBet > 0) {
            throw InvalidActionException.cannotBetFacingBet(player.getId(), Chips.of(currentBet));
        }

        if (amount.amount() < bigBlindAmount) {
            throw InvalidActionException.invalidRaiseAmount(
                    player.getId(), amount, Chips.of(bigBlindAmount));
        }

        if (!Chips.of(player.getChips()).canAfford(amount)) {
            throw InvalidActionException.insufficientChips(
                    player.getId(), amount, Chips.of(player.getChips()));
        }

        addToPot(player, amount.amount());
        currentBet = amount.amount();
        minRaise = amount.amount();
        lastRaiseAmount = amount.amount();
        lastAggressorId = player.getId();
        resetActionsForRaise();
    }

    private void executeRaise(Player player, Chips amount) {
        if (amount == null || amount.isZero()) {
            throw InvalidActionException.invalidBetAmount(player.getId(), 0);
        }

        if (currentBet == 0) {
            throw InvalidActionException.cannotRaiseNoBet(player.getId());
        }

        
        int targetTotalBet = amount.amount();
        int raiseIncrement = targetTotalBet - currentBet;

        if (raiseIncrement < minRaise) {
            throw InvalidActionException.invalidRaiseAmount(
                    player.getId(), Chips.of(Math.max(0, raiseIncrement)), Chips.of(minRaise));
        }

        int amountNeeded = targetTotalBet - player.getBetAmount();
        if (amountNeeded > player.getChips()) {
            throw InvalidActionException.insufficientChips(
                    player.getId(), Chips.of(amountNeeded), Chips.of(player.getChips()));
        }

        addToPot(player, amountNeeded);
        currentBet = targetTotalBet;
        minRaise = raiseIncrement;
        lastRaiseAmount = raiseIncrement;
        lastAggressorId = player.getId();
        resetActionsForRaise();
    }

    private void executeAllIn(Player player) {
        int allInAmount = player.getChips();
        addToPot(player, allInAmount);
        
        if (player.getBetAmount() > currentBet) {
            int raiseAmount = player.getBetAmount() - currentBet;
            currentBet = player.getBetAmount();
            if (raiseAmount >= minRaise) {
                minRaise = raiseAmount;
                lastRaiseAmount = raiseAmount;
                lastAggressorId = player.getId();
                resetActionsForRaise();
            }
        }
    }

    
    
    

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public GamePhase getPhase() {
        return phase;
    }

    public int getHandNumber() {
        return handNumber;
    }

    public boolean isFinished() {
        return finished;
    }

    public Chips getSmallBlind() {
        return Chips.of(smallBlindAmount);
    }

    public Chips getBigBlind() {
        return Chips.of(bigBlindAmount);
    }

    public int getMainPotAmount() {
        return potAmount;
    }

    public Chips getPotSize() {
        int total = potAmount;
        for (int sidePot : sidePotAmounts) {
            total += sidePot;
        }
        return Chips.of(total);
    }

    public int getCurrentPlayerIndex() {
        return currentPlayerIndex;
    }

    public List<Card> getDeck() {
        return Collections.unmodifiableList(deck);
    }

    public Chips getCurrentBet() {
        return Chips.of(currentBet);
    }

    public Chips getMinRaise() {
        return Chips.of(minRaise);
    }

    public int getDealerPosition() {
        return dealerPosition;
    }

    public int getButtonSeatPosition() {
        return buttonSeatPosition;
    }

    public boolean isDeadButton() {
        return deadButton;
    }

    public int getLastRaiseAmount() {
        return lastRaiseAmount;
    }

    public UUID getLastAggressorId() {
        return lastAggressorId;
    }

    public Map<Integer, Integer> getMissedBlinds() {
        return Collections.unmodifiableMap(missedBlinds);
    }

    
    public void addMissedBlind(int seatPosition, int amount) {
        missedBlinds.merge(seatPosition, amount, Integer::sum);
    }

    public String getWinnerName() {
        return winnerName;
    }

    public String getWinningHandDescription() {
        return winningHandDescription;
    }

    public List<UUID> getWinnerIds() {
        return Collections.unmodifiableList(winnerIds);
    }

    public List<Card> getCommunityCards() {
        return Collections.unmodifiableList(communityCards);
    }

    public List<Player> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public Player getCurrentPlayer() {
        if (players.isEmpty() || currentPlayerIndex >= players.size()) {
            return null;
        }
        Player player = players.get(currentPlayerIndex);
        if (player.isFolded() || player.isAllIn()) {
            return null;
        }
        return player;
    }

    public Player findPlayerById(UUID playerId) {
        return players.stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> PlayerNotFoundException.byIdInGame(playerId, id));
    }

    public List<Player> getActivePlayers() {
        return players.stream()
                .filter(p -> !p.isFolded() && p.getChips() >= 0)
                .collect(Collectors.toList());
    }

    public List<Player> getPlayersWithChips() {
        return players.stream()
                .filter(p -> p.getChips() > 0)
                .collect(Collectors.toList());
    }

    public BettingRound getCurrentBettingRound() {
        return new BettingRound(
                phase,
                Chips.of(currentBet),
                Chips.of(minRaise),
                actionsThisRound,
                lastAggressorId
        );
    }

    
    public List<DomainEvent> getDomainEvents() {
        return new ArrayList<>(domainEvents);
    }

    
    public void clearDomainEvents() {
        domainEvents.clear();
    }

    
    
    

    private void raiseEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    private void initializeDeck() {
        deck.clear();
        for (Suit suit : Suit.values()) {
            for (Value value : Value.values()) {
                deck.add(new Card(suit, value));
            }
        }
    }

    private void shuffleDeck() {
        if (fixedDeck != null) {
            deck.clear();
            deck.addAll(fixedDeck);
            return;
        }
        Collections.shuffle(deck);
    }

    /**
     * Test seam: force the next {@link #startNewHand()} to deal from {@code cards} in order instead of
     * shuffling (deal order: hole cards round-robin, then burn+flop, burn+turn, burn+river). Lets tests pin
     * deterministic showdown / side-pot outcomes. No-op effect in production (never called there).
     */
    void useFixedDeck(List<Card> cards) {
        this.fixedDeck = new ArrayList<>(cards);
    }

    private void dealHoleCards() {
        List<Player> activePlayers = getPlayersWithChips();
        for (int round = 0; round < 2; round++) {
            for (Player player : activePlayers) {
                if (!deck.isEmpty()) {
                    player.addCardToHand(deck.remove(0));
                }
            }
        }
    }

    private void postBlind(Player player, int amount, PlayerActed.ActionType actionType) {
        int actualAmount = Math.min(amount, player.getChips());
        addToPot(player, actualAmount);
        
        raiseEvent(new PlayerActed(
                id, player.getId(), player.getName(),
                actionType,
                Chips.of(actualAmount),
                phase,
                Chips.of(potAmount),
                Chips.of(player.getChips()),
                player.isAllIn()
        ));
    }

    private void addToPot(Player player, int amount) {
        int actualBet = player.placeBet(amount);
        potAmount += actualBet;
    }

    
    private void advanceButtonPosition() {
        if (players.isEmpty()) {
            return;
        }

        int maxSeatPosition = players.stream()
                .mapToInt(Player::getSeatPosition)
                .max()
                .orElse(0);

        int nextButtonSeat = (buttonSeatPosition + 1) % (maxSeatPosition + 1);

        Player playerAtButton = players.stream()
                .filter(p -> p.getSeatPosition() == nextButtonSeat && p.getChips() > 0)
                .findFirst()
                .orElse(null);

        if (playerAtButton != null) {
            buttonSeatPosition = nextButtonSeat;
            dealerPosition = players.indexOf(playerAtButton);
            deadButton = false;
        } else {
            buttonSeatPosition = nextButtonSeat;
            deadButton = true;

            for (int i = 1; i <= maxSeatPosition + 1; i++) {
                int checkSeat = (nextButtonSeat + i) % (maxSeatPosition + 1);
                Player nextPlayer = players.stream()
                        .filter(p -> p.getSeatPosition() == checkSeat && p.getChips() > 0)
                        .findFirst()
                        .orElse(null);
                if (nextPlayer != null) {
                    dealerPosition = players.indexOf(nextPlayer);
                    break;
                }
            }
        }
    }

    
    private void collectMissedBlinds(Player sbPlayer, Player bbPlayer) {
        if (missedBlinds.isEmpty()) {
            return;
        }
        for (Player player : getPlayersWithChips()) {
            if (player.equals(sbPlayer) || player.equals(bbPlayer)) {
                continue;
            }
            int missedAmount = missedBlinds.getOrDefault(player.getSeatPosition(), 0);
            if (missedAmount <= 0) {
                continue;
            }
            int actualPosted = Math.min(missedAmount, player.getChips());
            if (actualPosted > 0) {
                player.setChips(player.getChips() - actualPosted);
                potAmount += actualPosted;
                missedBlinds.remove(player.getSeatPosition());
                if (player.getChips() == 0) {
                    player.setAllIn(true);
                }
            }
        }
    }

    private int findNextActivePlayerIndex(int fromIndex) {
        int playersCount = players.size();
        for (int i = 1; i <= playersCount; i++) {
            int index = (fromIndex + i) % playersCount;
            Player player = players.get(index);
            if (!player.isFolded() && player.getChips() > 0) {
                return index;
            }
        }
        return fromIndex;
    }

    private void advanceToNextPlayer() {
        int startIndex = currentPlayerIndex;
        do {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
            Player player = players.get(currentPlayerIndex);
            if (!player.isFolded() && !player.isAllIn() && player.getChips() > 0) {
                return;
            }
        } while (currentPlayerIndex != startIndex);
    }

    private boolean isBettingRoundComplete() {
        List<Player> activePlayers = getActivePlayers();
        
        
        if (activePlayers.size() <= 1) {
            return true;
        }

        
        for (Player player : activePlayers) {
            if (!player.isAllIn()) {
                if (!player.hasActed()) {
                    return false;
                }
                if (player.getBetAmount() < currentBet) {
                    return false;
                }
            }
        }

        return true;
    }

    private void resetActionsForRaise() {
        for (Player player : players) {
            if (!player.isFolded() && !player.isAllIn()) {
                player.setHasActed(false);
            }
        }
        
        if (lastAggressorId != null) {
            findPlayerById(lastAggressorId).setHasActed(true);
        }
        actionsThisRound = 1;
    }

    private void advancePhase() {
        GamePhase previousPhase = phase;
        List<Card> newCards = new ArrayList<>();

        switch (phase) {
            case PRE_FLOP -> {
                dealCommunityCards(3); 
                newCards.addAll(communityCards);
                phase = GamePhase.FLOP;
            }
            case FLOP -> {
                dealCommunityCards(1); 
                newCards.add(communityCards.get(communityCards.size() - 1));
                phase = GamePhase.TURN;
            }
            case TURN -> {
                dealCommunityCards(1); 
                newCards.add(communityCards.get(communityCards.size() - 1));
                phase = GamePhase.RIVER;
            }
            case RIVER -> {
                phase = GamePhase.SHOWDOWN;
                determineWinner();
                return;
            }
            default -> {
                return;
            }
        }

        
        resetForNewBettingRound();

        
        raiseEvent(new PhaseChanged(
                id, previousPhase, phase,
                newCards, new ArrayList<>(communityCards),
                Chips.of(potAmount),
                getActivePlayers().size()
        ));

        
        if (countPlayersWhoCanAct() <= 1) {
            advancePhase();
        }
    }

    private void dealCommunityCards(int count) {
        
        if (!deck.isEmpty()) {
            deck.remove(0);
        }
        
        for (int i = 0; i < count && !deck.isEmpty(); i++) {
            communityCards.add(deck.remove(0));
        }
    }

    private void resetForNewBettingRound() {
        currentBet = 0;
        actionsThisRound = 0;
        lastAggressorId = null;

        for (Player player : players) {
            player.resetBetForNewRound();
        }

        
        currentPlayerIndex = findNextActivePlayerIndex(dealerPosition);
    }

    private int countPlayersWhoCanAct() {
        return (int) players.stream()
                .filter(p -> !p.isFolded() && !p.isAllIn() && p.getChips() > 0)
                .count();
    }

    private void determineWinner() {
        List<Player> playersInHand = getPlayersStillInHand();

        if (playersInHand.isEmpty()) {
            throw GameStateException.noActivePlayers(id);
        }

        List<HandCompleted.PotResult> potResults = distributePots(playersInHand);
        completeHand(true, potResults);
    }

    private void awardPotToLastPlayer(Player winner) {
        int amount = potAmount;
        winner.addWinnings(amount);

        winnerName = winner.getName();
        winningHandDescription = null;
        winnerIds.clear();
        winnerIds.add(winner.getId());

        raiseEvent(new PotAwarded(
                id, winner.getId(), winner.getName(),
                Chips.of(amount),
                null,
                Pot.PotType.MAIN
        ));

        potAmount = 0;

        List<HandCompleted.PotResult> potResults = List.of(
                new HandCompleted.PotResult(winner.getId(), winner.getName(), Chips.of(amount), null, false));
        completeHand(false, potResults);
    }

    
    private List<HandCompleted.PotResult> distributePots(List<Player> playersInHand) {
        List<PotShare> pots = calculateSidePots(playersInHand);
        List<HandCompleted.PotResult> results = new ArrayList<>();

        List<UUID> allWinnerIds = new ArrayList<>();
        int bestAmountWon = -1;
        String topWinnerName = null;
        String topHandDesc = null;

        for (PotShare pot : pots) {
            List<Player> eligible = playersInHand.stream()
                    .filter(p -> pot.eligiblePlayerIds().contains(p.getId()))
                    .collect(Collectors.toList());
            if (eligible.isEmpty()) {
                continue;
            }

            Map<Player, HandRanking> rankings = new HashMap<>();
            for (Player p : eligible) {
                HandRanking ranking = HandRanker.evaluate(p.getHand(), communityCards);
                if (ranking != null) {
                    rankings.put(p, ranking);
                }
            }

            List<Player> potWinners = findBestHands(rankings);
            if (potWinners.isEmpty()) {
                continue;
            }

            int winAmount = pot.amount() / potWinners.size();
            int remainder = pot.amount() % potWinners.size();
            boolean wasSplit = potWinners.size() > 1;

            for (int i = 0; i < potWinners.size(); i++) {
                Player winner = potWinners.get(i);
                int amount = winAmount + (i == 0 ? remainder : 0);
                winner.addWinnings(amount);

                HandRanking ranking = rankings.get(winner);
                String description = ranking != null ? ranking.getDescription() : "Winner (showdown)";

                results.add(new HandCompleted.PotResult(
                        winner.getId(), winner.getName(), Chips.of(amount), description, pot.isSide()));

                raiseEvent(new PotAwarded(
                        id, winner.getId(), winner.getName(), Chips.of(amount),
                        description,
                        pot.isSide() ? Pot.PotType.SIDE : Pot.PotType.MAIN,
                        wasSplit, potWinners.size()));

                if (!allWinnerIds.contains(winner.getId())) {
                    allWinnerIds.add(winner.getId());
                }
                if (amount > bestAmountWon) {
                    bestAmountWon = amount;
                    topWinnerName = winner.getName();
                    topHandDesc = description;
                }
            }
        }

        winnerName = topWinnerName;
        winningHandDescription = topHandDesc;
        winnerIds.clear();
        winnerIds.addAll(allWinnerIds);
        potAmount = 0;

        return results;
    }

    
    private List<PotShare> calculateSidePots(List<Player> playersInHand) {
        List<Integer> allInLevels = playersInHand.stream()
                .filter(Player::isAllIn)
                .map(Player::getTotalBetInRound)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<PotShare> pots = new ArrayList<>();

        if (allInLevels.isEmpty()) {
            List<UUID> eligibleIds = playersInHand.stream()
                    .map(Player::getId)
                    .collect(Collectors.toList());
            pots.add(new PotShare(potAmount, eligibleIds, false));
            return pots;
        }

        int previousLevel = 0;
        for (int level : allInLevels) {
            int amount = 0;
            List<UUID> eligibleIds = new ArrayList<>();
            for (Player p : playersInHand) {
                int contribution = Math.min(p.getTotalBetInRound(), level) - previousLevel;
                if (contribution > 0) {
                    amount += contribution;
                }
                if (p.getTotalBetInRound() >= level) {
                    eligibleIds.add(p.getId());
                }
            }
            if (amount > 0) {
                pots.add(new PotShare(amount, eligibleIds, !pots.isEmpty()));
            }
            previousLevel = level;
        }

        int maxAllIn = allInLevels.get(allInLevels.size() - 1);
        int topPotAmount = 0;
        List<UUID> topPotEligible = new ArrayList<>();
        for (Player p : playersInHand) {
            int extraContribution = p.getTotalBetInRound() - maxAllIn;
            if (extraContribution > 0) {
                topPotAmount += extraContribution;
                topPotEligible.add(p.getId());
            } else if (p.getTotalBetInRound() >= maxAllIn) {
                topPotEligible.add(p.getId());
            }
        }
        if (topPotAmount > 0 && !topPotEligible.isEmpty()) {
            pots.add(new PotShare(topPotAmount, topPotEligible, !pots.isEmpty()));
        }

        return pots;
    }

    private List<Player> findBestHands(Map<Player, HandRanking> rankings) {
        if (rankings.isEmpty()) {
            return List.of();
        }

        HandRanking bestRanking = rankings.values().stream()
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (bestRanking == null) {
            return List.of();
        }

        return rankings.entrySet().stream()
                .filter(e -> e.getValue().compareTo(bestRanking) == 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<Player> getPlayersStillInHand() {
        return players.stream()
                .filter(p -> !p.isFolded())
                .collect(Collectors.toList());
    }

    private void completeHand(boolean wentToShowdown, List<HandCompleted.PotResult> potResults) {
        Duration duration = handStartTime != null 
                ? Duration.between(handStartTime, Instant.now())
                : Duration.ZERO;

        Map<UUID, Chips> playerChipsAfter = new HashMap<>();
        for (Player player : players) {
            playerChipsAfter.put(player.getId(), Chips.of(player.getChips()));
        }

        phase = GamePhase.FINISHED;

        raiseEvent(new HandCompleted(
                id, handNumber,
                potResults,
                playerChipsAfter,
                duration,
                actionsThisRound,
                wentToShowdown
        ));

        
        checkForEliminatedPlayers();

        
        if (getPlayersWithChips().size() < MIN_PLAYERS) {
            finished = true;
        }
    }

    
    private record PotShare(int amount, List<UUID> eligiblePlayerIds, boolean isSide) {
    }

    private void checkForEliminatedPlayers() {
        int position = getPlayersWithChips().size() + 1;
        for (Player player : players) {
            if (player.getChips() <= 0 && !player.isFolded()) {
                raiseEvent(new PlayerEliminated(
                        id, player.getId(), player.getName(),
                        position,
                        Chips.zero(),
                        handNumber
                ));
            }
        }
    }

    private PlayerActed.ActionType toEventActionType(PlayerAction action) {
        return switch (action) {
            case FOLD -> PlayerActed.ActionType.FOLD;
            case CHECK -> PlayerActed.ActionType.CHECK;
            case CALL -> PlayerActed.ActionType.CALL;
            case BET -> PlayerActed.ActionType.BET;
            case RAISE -> PlayerActed.ActionType.RAISE;
            case ALL_IN -> PlayerActed.ActionType.ALL_IN;
        };
    }
}
