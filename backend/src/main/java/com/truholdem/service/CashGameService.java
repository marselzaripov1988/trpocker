package com.truholdem.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.domain.event.DomainEvent;
import com.truholdem.domain.event.PotAwarded;
import com.truholdem.domain.value.Chips;
import com.truholdem.mapper.PokerGameMapper;
import com.truholdem.model.CashChipScale;
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;
import com.truholdem.model.CashTable;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Player;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.repository.GameRepository;
import com.truholdem.service.wallet.CashGameWalletService;

/**
 * Drives a cash (ring) hand on the pure {@link PokerGame} aggregate kernel and settles it back to the seats and
 * the wallet. The table's real-money stakes/stacks are mapped to the engine's integer chips via
 * {@link CashChipScale}; after the hand finishes the rake is taken from each awarded pot (recorded as house
 * revenue), the players' final stacks are written back to their {@link CashSeat}s in money, and any seat that
 * asked to leave is cashed out. Tournaments are untouched — they keep the default legacy engine.
 *
 * <p>This slice provides the start + settlement seam; persisting the live hand between actions and cluster
 * hot-state for an indefinitely-live table are later slices. Each seated player maps to an engine player by
 * name (the kernel assigns fresh player ids on create), so a table's live seats must have distinct names.
 */
@Service
public class CashGameService {

    private static final Logger log = LoggerFactory.getLogger(CashGameService.class);

    private final CashTableRepository cashTableRepository;
    private final CashSeatRepository cashSeatRepository;
    private final CashRakeService rakeService;
    private final CashGameWalletService walletBridge;
    private final GameRepository gameRepository;
    private final PokerGameMapper gameMapper;

    public CashGameService(CashTableRepository cashTableRepository, CashSeatRepository cashSeatRepository,
            CashRakeService rakeService, CashGameWalletService walletBridge,
            GameRepository gameRepository, PokerGameMapper gameMapper) {
        this.cashTableRepository = cashTableRepository;
        this.cashSeatRepository = cashSeatRepository;
        this.rakeService = rakeService;
        this.walletBridge = walletBridge;
        this.gameRepository = gameRepository;
        this.gameMapper = gameMapper;
    }

    /**
     * Start a new hand for the table from its ACTIVE seats: map each seat's money stack to engine chips, build
     * the aggregate game with the table's fixed blinds (in chips) and deal the hand. Returns the live aggregate
     * game (the caller drives actions and then calls {@link #settleHand}).
     */
    @Transactional(readOnly = true)
    public PokerGame startHand(UUID tableId) {
        return buildHand(requireTable(tableId));
    }

    /** Build (deal) a fresh aggregate hand for the table's ACTIVE seats; does not persist. */
    private PokerGame buildHand(CashTable table) {
        if (!table.isActive()) {
            throw new IllegalStateException("Cash table " + table.getId() + " is closed");
        }
        CashChipScale scale = CashChipScale.forTable(table);
        List<CashSeat> active = cashSeatRepository.findByCashTableIdAndStatus(table.getId(), CashSeatStatus.ACTIVE);
        if (active.size() < 2) {
            throw new IllegalStateException("Need at least 2 active seats to start a hand at table " + table.getId());
        }
        List<PlayerInfo> infos = new ArrayList<>(active.size());
        for (CashSeat seat : active) {
            infos.add(new PlayerInfo(seat.getPlayerName(), scale.toChips(seat.getStack()), false));
        }
        PokerGame game = PokerGame.create(infos,
                Chips.of(scale.toChips(table.getSmallBlind())),
                Chips.of(scale.toChips(table.getBigBlind())));
        game.startNewHand();
        return game;
    }

    /**
     * Open a new live hand at the table and persist it (one live hand per table). The hand is stored as a
     * {@code games} row via the aggregate↔JPA mapper — so hole cards / deck survive between actions and nodes —
     * and the table is linked to it. Returns the persisted game id; drive it with {@link #act} and the hand
     * settles itself when it finishes.
     */
    @Transactional
    public UUID openHand(UUID tableId) {
        CashTable table = requireTable(tableId);
        if (table.getCurrentGameId() != null) {
            throw new IllegalStateException("Table " + tableId + " already has a live hand");
        }
        PokerGame aggregate = buildHand(table);
        Game game = new Game();
        gameMapper.applyToGame(aggregate, game);
        Game saved = gameRepository.save(game);
        table.setCurrentGameId(saved.getId());
        cashTableRepository.save(table);
        log.info("Opened cash hand {} at table {}", saved.getId(), tableId);
        return saved.getId();
    }

    /** Reconstitute the table's current live hand from storage, or null when the table is idle. */
    @Transactional(readOnly = true)
    public PokerGame peekHand(UUID tableId) {
        UUID gameId = requireTable(tableId).getCurrentGameId();
        if (gameId == null) {
            return null;
        }
        return gameMapper.fromGame(gameRepository.findById(gameId)
                .orElseThrow(() -> new NoSuchElementException("Cash hand not found: " + gameId)));
    }

    /**
     * Apply one action to the table's live hand: load it from storage, execute the action, and either persist
     * the advanced state (hand continues) or settle the hand and free the table (hand finished). The hand state
     * — including hole cards — is reloaded from the DB each call, so play survives across requests and nodes.
     */
    @Transactional
    public CashActResult act(UUID tableId, UUID enginePlayerId, PlayerAction action, Integer amountChips) {
        CashTable table = requireTable(tableId);
        UUID gameId = table.getCurrentGameId();
        if (gameId == null) {
            throw new IllegalStateException("Table " + tableId + " has no live hand");
        }
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new NoSuchElementException("Cash hand not found: " + gameId));
        PokerGame aggregate = gameMapper.fromGame(game);
        aggregate.executeAction(enginePlayerId, action, amountChips != null ? Chips.of(amountChips) : null);

        if (aggregate.getPhase() == GamePhase.FINISHED) {
            CashHandResult settlement = settleHand(tableId, aggregate);
            table.setCurrentGameId(null);
            cashTableRepository.save(table);
            gameRepository.delete(game);
            return new CashActResult(true, settlement.totalRake(), settlement.cashedOut());
        }
        gameMapper.applyToGame(aggregate, game);
        gameRepository.save(game);
        return new CashActResult(false, BigDecimal.ZERO, List.of());
    }

    /**
     * Settle a finished cash hand: take the rake from each awarded pot (no-flop-no-drop — only a contested pot
     * that saw a flop is raked), write the players' final stacks back to their seats in money, and cash out any
     * seat that requested to leave. Idempotent rake recording per (game, pot). Returns the rake taken and the
     * players cashed out.
     */
    @Transactional
    public CashHandResult settleHand(UUID tableId, PokerGame finishedGame) {
        // The hand is complete when its phase reaches FINISHED; isFinished() means the whole match is over
        // (a player busted), which is not the cash-table end condition.
        if (finishedGame.getPhase() != GamePhase.FINISHED) {
            throw new IllegalStateException("Hand " + finishedGame.getId() + " is not finished");
        }
        CashTable table = requireTable(tableId);
        CashChipScale scale = CashChipScale.forTable(table);

        Map<String, CashSeat> seatByName = new HashMap<>();
        for (CashSeat seat : cashSeatRepository.findByCashTableId(tableId)) {
            if (seat.isSeated()) {
                seatByName.put(seat.getPlayerName(), seat);
            }
        }

        // Final stack per played seat, in money (chips -> money at the table scale).
        Map<CashSeat, BigDecimal> stacks = new HashMap<>();
        for (Player player : finishedGame.getPlayers()) {
            CashSeat seat = seatByName.get(player.getName());
            if (seat != null) {
                stacks.put(seat, scale.toMoney(player.getChips()));
            }
        }

        // Rake: only a contested pot (a flop was dealt) is raked; taken from the winner of each awarded pot.
        boolean contested = finishedGame.getCommunityCards().size() >= 3;
        BigDecimal totalRake = BigDecimal.ZERO;
        int potIndex = 0;
        for (DomainEvent event : finishedGame.getDomainEvents()) {
            if (!(event instanceof PotAwarded awarded)) {
                continue;
            }
            BigDecimal potMoney = scale.toMoney(awarded.getAmount().amount());
            String key = "cashrake:" + finishedGame.getId() + ":" + potIndex++;
            BigDecimal rake = rakeService.collectRake(table, potMoney, contested, key);
            if (rake.signum() <= 0) {
                continue;
            }
            Player winner = finishedGame.findPlayerById(awarded.getWinnerId());
            CashSeat winnerSeat = seatByName.get(winner.getName());
            if (winnerSeat != null) {
                stacks.computeIfPresent(winnerSeat, (s, money) -> money.subtract(rake));
            }
            totalRake = totalRake.add(rake);
        }

        stacks.forEach((seat, money) -> {
            seat.setStack(money);
            cashSeatRepository.save(seat);
        });

        // Deferred cash-out: a seat that asked to leave is settled now that the hand has finished.
        List<UUID> cashedOut = new ArrayList<>();
        for (CashSeat seat : stacks.keySet()) {
            if (seat.getStatus() == CashSeatStatus.LEAVING) {
                walletBridge.cashOut(seat.getPlayerId(), tableId);
                cashedOut.add(seat.getPlayerId());
            }
        }

        log.info("Settled cash hand {} at table {}: rake {} {}, {} player(s) cashed out",
                finishedGame.getId(), tableId, totalRake, table.getAsset(), cashedOut.size());
        return new CashHandResult(totalRake, cashedOut);
    }

    /** Create a cash table; validates the blinds/buy-in produce a sane integer chip range. */
    @Transactional
    public CashTable createTable(String name, com.truholdem.model.CryptoAsset asset, BigDecimal smallBlind,
            BigDecimal bigBlind, BigDecimal minBuyIn, BigDecimal maxBuyIn, int maxSeats, int rakeBasisPoints,
            BigDecimal rakeCap) {
        CashChipScale.forBlinds(smallBlind, bigBlind).toChips(maxBuyIn); // rejects a config that overflows chips
        CashTable table = new CashTable(name, asset, smallBlind, bigBlind, minBuyIn, maxBuyIn, maxSeats,
                rakeBasisPoints, rakeCap);
        return cashTableRepository.save(table);
    }

    /** Sit down at a cash table: debit the wallet for the buy-in and seat the player. */
    @Transactional
    public CashSeat sit(UUID userId, UUID tableId, String playerName, BigDecimal buyIn) {
        return walletBridge.buyIn(userId, tableId, playerName, buyIn);
    }

    /** Top up an active seat between hands (debit wallet, raise stack up to the table max buy-in). */
    @Transactional
    public CashSeat topUp(UUID userId, UUID tableId, BigDecimal amount) {
        return walletBridge.topUp(userId, tableId, amount);
    }

    @Transactional(readOnly = true)
    public List<CashTable> listActiveTables() {
        return cashTableRepository.findByActiveTrueOrderBySmallBlindAsc();
    }

    @Transactional(readOnly = true)
    public CashTable getTable(UUID tableId) {
        return requireTable(tableId);
    }

    /** Seats currently at the table (not yet stood up), in seat order. */
    @Transactional(readOnly = true)
    public List<CashSeat> seatsOf(UUID tableId) {
        return cashSeatRepository.findByCashTableId(tableId).stream()
                .filter(CashSeat::isSeated)
                .sorted(java.util.Comparator.comparingInt(CashSeat::getSeatNumber))
                .toList();
    }

    /**
     * Apply an action on behalf of the authenticated user: resolve their seat → the engine player (by name) in
     * the current hand, convert any money amount to chips, and {@link #act}.
     */
    @Transactional
    public CashActResult actAsUser(UUID tableId, UUID userId, PlayerAction action, BigDecimal amount) {
        CashTable table = requireTable(tableId);
        CashSeat seat = cashSeatRepository
                .findByCashTableIdAndPlayerIdAndStatusNot(tableId, userId, CashSeatStatus.LEFT)
                .orElseThrow(() -> new IllegalStateException("You are not seated at table " + tableId));
        UUID gameId = table.getCurrentGameId();
        if (gameId == null) {
            throw new IllegalStateException("No live hand at table " + tableId);
        }
        PokerGame view = gameMapper.fromGame(gameRepository.findById(gameId)
                .orElseThrow(() -> new NoSuchElementException("Cash hand not found: " + gameId)));
        Player enginePlayer = view.getPlayers().stream()
                .filter(p -> p.getName().equals(seat.getPlayerName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("You are not in the current hand"));
        Integer chips = amount != null ? CashChipScale.forTable(table).toChips(amount) : null;
        return act(tableId, enginePlayer.getId(), action, chips);
    }

    /**
     * Stand up: if a hand is live the seat is marked LEAVING and cashed out when the hand finishes; otherwise it
     * is cashed out immediately. Returns whether the cash-out happened now and the amount credited.
     */
    @Transactional
    public CashLeaveResult leaveTable(UUID userId, UUID tableId) {
        CashTable table = requireTable(tableId);
        if (table.getCurrentGameId() != null) {
            walletBridge.requestLeave(userId, tableId);
            return new CashLeaveResult(false, BigDecimal.ZERO);
        }
        BigDecimal credited = walletBridge.cashOut(userId, tableId);
        return new CashLeaveResult(true, credited);
    }

    private CashTable requireTable(UUID tableId) {
        return cashTableRepository.findById(tableId)
                .orElseThrow(() -> new NoSuchElementException("Cash table not found: " + tableId));
    }

    /** Outcome of settling a cash hand: the rake taken (house revenue) and the players cashed out on leaving. */
    public record CashHandResult(BigDecimal totalRake, List<UUID> cashedOut) {
    }

    /**
     * Outcome of applying one action: whether it completed the hand and, if so, the settlement (rake taken +
     * players cashed out). When the hand continues, {@code handComplete} is false with a zero/empty settlement.
     */
    public record CashActResult(boolean handComplete, BigDecimal totalRake, List<UUID> cashedOut) {
    }

    /** Outcome of leaving: whether the cash-out happened now (vs deferred to hand end) and the amount credited. */
    public record CashLeaveResult(boolean cashedOutNow, BigDecimal amount) {
    }
}
