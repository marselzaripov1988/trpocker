package com.truholdem.service.tournament;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.dto.FederationDetailResponse;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationFinalBuyout;
import com.truholdem.model.PyramidFederationRegistration;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentFeeEntry;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationFinalBuyoutRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.repository.TournamentFeeEntryRepository;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.notification.TournamentNotificationService;
import com.truholdem.service.tournament.PyramidTournamentService.PyramidRunResult;
import com.truholdem.service.wallet.TournamentWalletService;
import com.truholdem.service.wallet.WalletService;

/**
 * Orchestrates the registration + wave-fill phase of a federated pyramid. Players register into the
 * federation and are assigned to shards in fill order ({@link #register}); a filled shard becomes
 * {@link FederationShardStatus#READY}. Materializing a shard into its child pyramid tournament (creating it,
 * seeding its registrations, starting it) is deferred to {@link #promoteShards}, which respects the
 * {@code app.tournament.federated-max-concurrent-shards} wave cap — so the heavy work is decoupled from the
 * fast registration path. Each shard is pinned to a node-group (round-robin) for physical sharding.
 *
 * <p>Play-money for now; running shards to a winner and the final are later slices.
 */
@Service
public class FederatedPyramidService {

    private static final Logger log = LoggerFactory.getLogger(FederatedPyramidService.class);

    private final PyramidFederationRepository federationRepository;
    private final PyramidFederationShardRepository shardRepository;
    private final PyramidFederationRegistrationRepository registrationRepository;
    private final PyramidFederationFinalBuyoutRepository finalBuyoutRepository;
    private final TournamentService tournamentService;
    private final PyramidTournamentService pyramidTournamentService;
    private final TournamentNotificationService notificationService;
    private final WalletService walletService;
    private final TournamentWalletService tournamentWalletService;
    private final TournamentFeeEntryRepository feeEntryRepository;
    private final TournamentRegistrationRepository tournamentRegistrationRepository;
    private final FederationPlayerWalletService walletPoolService;
    private final org.springframework.beans.factory.ObjectProvider<
            com.truholdem.service.wallet.sol.SolanaRpcClient> solRpc;
    private final AppProperties.Tournament tournamentProperties;
    private final AppProperties.Payments paymentsProperties;
    /** Self proxy, so {@link #recordShardWinner} runs in its own transaction when called internally. */
    private final FederatedPyramidService self;

    public FederatedPyramidService(PyramidFederationRepository federationRepository,
            PyramidFederationShardRepository shardRepository,
            PyramidFederationRegistrationRepository registrationRepository,
            PyramidFederationFinalBuyoutRepository finalBuyoutRepository,
            TournamentService tournamentService,
            PyramidTournamentService pyramidTournamentService,
            TournamentNotificationService notificationService, WalletService walletService,
            TournamentWalletService tournamentWalletService,
            TournamentFeeEntryRepository feeEntryRepository,
            TournamentRegistrationRepository tournamentRegistrationRepository,
            FederationPlayerWalletService walletPoolService,
            org.springframework.beans.factory.ObjectProvider<
                    com.truholdem.service.wallet.sol.SolanaRpcClient> solRpc,
            AppProperties appProperties, @Lazy FederatedPyramidService self) {
        this.federationRepository = federationRepository;
        this.shardRepository = shardRepository;
        this.registrationRepository = registrationRepository;
        this.finalBuyoutRepository = finalBuyoutRepository;
        this.tournamentService = tournamentService;
        this.pyramidTournamentService = pyramidTournamentService;
        this.notificationService = notificationService;
        this.walletService = walletService;
        this.tournamentWalletService = tournamentWalletService;
        this.feeEntryRepository = feeEntryRepository;
        this.tournamentRegistrationRepository = tournamentRegistrationRepository;
        this.walletPoolService = walletPoolService;
        this.solRpc = solRpc;
        this.tournamentProperties = appProperties.getTournament();
        this.paymentsProperties = appProperties.getPayments();
        this.self = self;
    }

    /**
     * Create a federation and its shard skeleton from a {@link FederatedPyramidPlan}. Seats/hands are taken
     * from the pyramid config so the shards match the engine. Shard 0 opens for registration; the rest are
     * PENDING. {@code registrationDeadline == null} means an indefinite registration window.
     */
    @Transactional
    public PyramidFederation createFederation(String name, long startingPlayers, int shardSize,
            Instant registrationDeadline) {
        return createFederation(name, startingPlayers, shardSize, registrationDeadline, null, null);
    }

    /** As above, with an optional real-money buy-in charged on registration (null/zero asset = play-money). */
    @Transactional
    public PyramidFederation createFederation(String name, long startingPlayers, int shardSize,
            Instant registrationDeadline, BigDecimal buyInAmount, CryptoAsset buyInAsset) {
        return createFederation(name, startingPlayers, shardSize, registrationDeadline,
                buyInAmount, buyInAsset, false);
    }

    /**
     * As above, with an optional buy-up variant: when {@code buyUpEnabled}, each shard is a buy-up pyramid
     * where players can buy guaranteed higher-level seats before the shard starts. Buy-up requires a
     * real-money buy-in (charged when the player is seated into their shard, not at federation registration).
     */
    @Transactional
    public PyramidFederation createFederation(String name, long startingPlayers, int shardSize,
            Instant registrationDeadline, BigDecimal buyInAmount, CryptoAsset buyInAsset, boolean buyUpEnabled) {
        return createFederation(name, startingPlayers, shardSize, registrationDeadline, buyInAmount, buyInAsset,
                buyUpEnabled, 0);
    }

    /**
     * As above, with a house commission on the prize pool: {@code feeBasisPoints} (0–2000 bps = 0–20%) is
     * taken off whichever crypto pool pays out (the flat federation pool, the buy-up final distribution, and
     * each buy-up shard child — children inherit the rate). 0 = no fee (existing behaviour).
     */
    @Transactional
    public PyramidFederation createFederation(String name, long startingPlayers, int shardSize,
            Instant registrationDeadline, BigDecimal buyInAmount, CryptoAsset buyInAsset, boolean buyUpEnabled,
            int feeBasisPoints) {
        return createFederation(name, startingPlayers, shardSize, registrationDeadline, buyInAmount, buyInAsset,
                buyUpEnabled, feeBasisPoints, false);
    }

    /**
     * As above, with the <b>isolated-custody</b> variant: each player pays their buy-in on-chain into a dedicated
     * per-player wallet (Solana USDT) instead of an off-chain {@code chargeBuyIn}. Requires a USDT_SOL buy-in and
     * the {@code app.tournament.federated-isolated-wallets-enabled} flag; incompatible with buy-up.
     */
    @Transactional
    public PyramidFederation createFederation(String name, long startingPlayers, int shardSize,
            Instant registrationDeadline, BigDecimal buyInAmount, CryptoAsset buyInAsset, boolean buyUpEnabled,
            int feeBasisPoints, boolean isolatedWalletsEnabled) {
        if (buyUpEnabled && (buyInAmount == null || buyInAmount.signum() <= 0 || buyInAsset == null)) {
            throw new IllegalArgumentException("A buy-up federated pyramid requires a real-money buy-in");
        }
        if (isolatedWalletsEnabled) {
            if (!tournamentProperties.isFederatedIsolatedWalletsEnabled()) {
                throw new IllegalStateException("Isolated-custody federated pyramid is disabled "
                        + "(app.tournament.federated-isolated-wallets-enabled)");
            }
            if (buyInAmount == null || buyInAmount.signum() <= 0 || buyInAsset != CryptoAsset.USDT_SOL) {
                throw new IllegalArgumentException(
                        "Isolated-custody federation requires a USDT_SOL buy-in (Solana-first)");
            }
            if (buyUpEnabled) {
                throw new IllegalArgumentException("Isolated custody is incompatible with buy-up");
            }
        }
        int seats = tournamentProperties.getPyramidDefaultSeatsPerTable();
        int hands = tournamentProperties.getPyramidDefaultHandsPerRound();
        FederatedPyramidPlan plan = new FederatedPyramidPlan(startingPlayers, shardSize, seats);

        PyramidFederation federation = new PyramidFederation(
                name, shardSize, plan.shardCount(), seats, hands, registrationDeadline);
        if (buyInAmount != null && buyInAmount.signum() > 0 && buyInAsset != null) {
            federation.setCryptoBuyInAmount(buyInAmount);
            federation.setCryptoBuyInAsset(buyInAsset);
        }
        federation.setBuyUpEnabled(buyUpEnabled);
        federation.setIsolatedWalletsEnabled(isolatedWalletsEnabled);
        federation.setFeeBasisPoints(feeBasisPoints); // validates 0..2000
        // Snapshot the current global prize config onto the federation so an admin can tune it per-federation.
        federation.setShardWinnerPpm(tournamentProperties.getFederatedShardWinnerPpm());
        federation.setFinalTablePlaceBps(csv(tournamentProperties.getFederatedFinalTablePlaceBps()));
        federation.setFinalTableRestBps(tournamentProperties.getFederatedFinalTableRestBps());
        federation = federationRepository.save(federation);

        int nodeGroups = Math.max(1, tournamentProperties.getFederatedNodeGroupCount());
        List<PyramidFederationShard> shards = new ArrayList<>(plan.shardCount());
        for (int i = 0; i < plan.shardCount(); i++) {
            PyramidFederationShard shard =
                    new PyramidFederationShard(federation.getId(), i, "ng-" + (i % nodeGroups));
            if (i == 0) {
                shard.markRegistering();
            }
            shards.add(shard);
        }
        shardRepository.saveAll(shards);
        log.info("Created federated pyramid {} ({} shards of {} → {} finalists)",
                federation.getId(), plan.shardCount(), shardSize, plan.finalistsCount());
        return federation;
    }

    /**
     * Register a player into the federation, assigning them to the open fill shard (lowest-index REGISTERING
     * shard with spare capacity). When a shard fills it becomes READY and the next PENDING shard opens.
     * Idempotent per (federation, player). Does NOT materialize shards — call {@link #promoteShards} for that.
     */
    @Transactional
    public PyramidFederationShard register(UUID federationId, UUID playerId, String playerName) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.REGISTERING
                && federation.getStatus() != FederationStatus.SHARDS_RUNNING) {
            throw new IllegalStateException("Federation " + federationId + " is not accepting registrations");
        }
        if (federation.isIsolatedWalletsEnabled()) {
            throw new IllegalStateException("Isolated-custody federation uses the dedicated-wallet registration flow");
        }
        if (registrationRepository.existsByFederationIdAndPlayerId(federationId, playerId)) {
            return shardForExistingPlayer(federationId, playerId);
        }
        PyramidFederationShard shard = openFillShard(federationId)
                .orElseThrow(() -> new IllegalStateException("Federation is full — all shards are filled"));

        if (federation.isRealMoney() && !federation.isBuyUpEnabled()) {
            // Charges the wallet; throws InsufficientFundsException (rolling back the registration) if too low.
            // Buy-up federations charge the buy-in later, when the player is seated into their shard.
            walletService.chargeBuyIn(playerId, federation.getCryptoBuyInAsset(),
                    federation.getCryptoBuyInAmount(), "fedbuyin:" + federationId + ":" + playerId);
        }
        registrationRepository.save(
                new PyramidFederationRegistration(federationId, shard.getShardIndex(), playerId, playerName));
        shard.incrementFilled();
        if (shard.getFilledCount() >= federation.getShardSize()) {
            shard.markReady();
            openNextPendingShard(federationId);
        }
        return shardRepository.save(shard);
    }

    /** Import a batch of offline-generated dedicated wallets into an isolated-custody federation (admin). */
    @Transactional
    public int importPlayerWallets(UUID federationId,
            List<com.truholdem.dto.FederationWalletImportRequest.Entry> entries) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isIsolatedWalletsEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not an isolated-custody federation");
        }
        return walletPoolService.importBatch(federationId, federation.getCryptoBuyInAsset(), entries);
    }

    /**
     * Isolated-custody registration: assign the player a dedicated on-chain wallet (Solana USDT ATA) to pay the
     * buy-in into, and record an unconfirmed/unseated registration. The player only takes a shard seat once the
     * deposit confirms (see {@link #confirmDeposit}). Idempotent — re-registering returns the same wallet.
     */
    @Transactional
    public FederationPlayerWallet registerIsolated(UUID federationId, UUID playerId, String playerName) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isIsolatedWalletsEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not an isolated-custody federation");
        }
        if (federation.getStatus() != FederationStatus.REGISTERING
                && federation.getStatus() != FederationStatus.SHARDS_RUNNING) {
            throw new IllegalStateException("Federation " + federationId + " is not accepting registrations");
        }
        FederationPlayerWallet wallet = walletPoolService.assign(federationId, playerId);
        if (!registrationRepository.existsByFederationIdAndPlayerId(federationId, playerId)) {
            registrationRepository.save(
                    new PyramidFederationRegistration(federationId, playerId, playerName, wallet.getAddress()));
        }
        return wallet;
    }

    /**
     * Record a confirmed on-chain buy-in deposit at a player's dedicated wallet: mark it FUNDED and seat the
     * player into a shard (fill order). Idempotent — returns false below the confirmation threshold, when
     * underfunded, or for an already-funded wallet.
     */
    @Transactional
    public boolean confirmDeposit(UUID federationId, String walletAddress, String depositTxId, BigDecimal amount,
            int confirmations) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isIsolatedWalletsEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not an isolated-custody federation");
        }
        int min = Math.max(0, paymentsProperties.getMinConfirmations());
        if (confirmations < min || amount == null || amount.compareTo(federation.getCryptoBuyInAmount()) < 0) {
            return false;
        }
        Optional<UUID> player = walletPoolService.confirmFunding(federationId, walletAddress, depositTxId, amount);
        if (player.isEmpty()) {
            return false; // already funded / unassigned
        }
        PyramidFederationShard shard = openFillShard(federationId)
                .orElseThrow(() -> new IllegalStateException("Federation is full — all shards are filled"));
        PyramidFederationRegistration reg = registrationRepository
                .findByFederationIdAndPlayerId(federationId, player.get())
                .orElseThrow(() -> new IllegalStateException("No registration for funded wallet " + walletAddress));
        reg.confirmDepositAndSeat(shard.getShardIndex());
        registrationRepository.save(reg);
        shard.incrementFilled();
        if (shard.getFilledCount() >= federation.getShardSize()) {
            shard.markReady();
            openNextPendingShard(federationId);
        }
        shardRepository.save(shard);
        log.info("Federation {} — deposit confirmed for player {} → seated in shard {}",
                federationId, player.get(), shard.getShardIndex());
        return true;
    }

    /** Up to this many token accounts per {@code getMultipleAccounts} call (Solana's documented cap). */
    private static final int DEPOSIT_BALANCE_BATCH = 100;

    /**
     * Poll the assigned-but-unfunded wallets' on-chain USDT balances and confirm those holding ≥ the buy-in.
     * Balances are read in batches of {@value #DEPOSIT_BALANCE_BATCH} via {@code getMultipleAccounts}, so a large
     * field costs ceil(N/100) RPC calls rather than N — the background poller ({@code FederationDepositPollScheduler})
     * and the admin button share this. Returns the number newly seated.
     */
    public int reconcileDeposits(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isIsolatedWalletsEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not an isolated-custody federation");
        }
        com.truholdem.service.wallet.sol.SolanaRpcClient rpc = solRpc.getIfAvailable();
        if (rpc == null) {
            throw new IllegalStateException("Solana RPC is disabled (app.payments.sol-rpc-enabled)");
        }
        int decimals = federation.getCryptoBuyInAsset().getDecimals();
        BigInteger minBaseUnits = federation.getCryptoBuyInAmount().movePointRight(decimals).toBigIntegerExact();
        int min = Math.max(0, paymentsProperties.getMinConfirmations());
        List<FederationPlayerWallet> awaiting = walletPoolService.assignedAwaitingDeposit(federationId);
        int seated = 0;
        for (int from = 0; from < awaiting.size(); from += DEPOSIT_BALANCE_BATCH) {
            List<FederationPlayerWallet> batch = awaiting.subList(from, Math.min(from + DEPOSIT_BALANCE_BATCH, awaiting.size()));
            try {
                java.util.Map<String, BigInteger> balances =
                        rpc.getTokenAccountBalances(batch.stream().map(FederationPlayerWallet::getAddress).toList());
                for (FederationPlayerWallet wallet : batch) {
                    BigInteger balance = balances.getOrDefault(wallet.getAddress(), BigInteger.ZERO);
                    if (balance.compareTo(minBaseUnits) >= 0) {
                        BigDecimal amount = new BigDecimal(balance).movePointLeft(decimals);
                        if (self.confirmDeposit(federationId, wallet.getAddress(),
                                "soldep:" + federationId + ":" + wallet.getAddress(), amount, min)) {
                            seated++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Federation {} — deposit balance batch [{}..{}) failed (will retry): {}",
                        federationId, from, from + batch.size(), e.toString());
            }
        }
        return seated;
    }

    /**
     * Release assigned-but-unfunded dedicated wallets older than the deposit window (no-shows) back to the FREE
     * pool and drop their pending registrations, so the wallet can be re-used. Run {@link #reconcileDeposits}
     * first so a genuine late deposit is seated rather than released. Returns the number released.
     */
    @Transactional
    public int releaseNoShows(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isIsolatedWalletsEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not an isolated-custody federation");
        }
        Instant cutoff = Instant.now().minus(
                tournamentProperties.getFederatedIsolatedDepositWindowMinutes(), java.time.temporal.ChronoUnit.MINUTES);
        int released = 0;
        for (FederationPlayerWallet wallet : walletPoolService.assignedAwaitingDeposit(federationId)) {
            if (wallet.getAssignedAt() == null || !wallet.getAssignedAt().isBefore(cutoff)) {
                continue;
            }
            UUID playerId = wallet.getAssignedPlayerId();
            walletPoolService.release(wallet);
            registrationRepository.findByFederationIdAndPlayerId(federationId, playerId)
                    .filter(reg -> !reg.isDepositConfirmed())
                    .ifPresent(registrationRepository::delete);
            released++;
        }
        if (released > 0) {
            log.info("Federation {} — released {} no-show (unfunded) wallet(s)", federationId, released);
        }
        return released;
    }

    /**
     * Bulk-register {@code count} synthetic bot players (play-money federations only) for load tests and
     * simulations: fills shards in index order (flipping each full shard to READY and opening the next), in a
     * single batched insert rather than per-player. Returns the number actually placed (fewer if the
     * federation fills up first).
     */
    @Transactional
    public int registerBotsBatch(UUID federationId, int count, String namePrefix) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.REGISTERING
                && federation.getStatus() != FederationStatus.SHARDS_RUNNING) {
            throw new IllegalStateException("Federation " + federationId + " is not accepting registrations");
        }
        if (federation.isRealMoney()) {
            throw new IllegalStateException("Bot batch registration is only allowed for play-money federations");
        }
        String prefix = (namePrefix == null || namePrefix.isBlank()) ? "Bot_" : namePrefix;
        long seq = registrationRepository.countByFederationId(federationId);
        int remaining = count;
        int placed = 0;
        List<PyramidFederationRegistration> regs = new ArrayList<>();
        List<PyramidFederationShard> touched = new ArrayList<>();
        for (PyramidFederationShard shard : shardRepository.findByFederationIdOrderByShardIndexAsc(federationId)) {
            if (remaining == 0) {
                break;
            }
            if (shard.getStatus() != FederationShardStatus.PENDING
                    && shard.getStatus() != FederationShardStatus.REGISTERING) {
                continue;
            }
            if (shard.getStatus() == FederationShardStatus.PENDING) {
                shard.markRegistering();
            }
            int take = Math.min(federation.getShardSize() - shard.getFilledCount(), remaining);
            for (int j = 0; j < take; j++) {
                regs.add(new PyramidFederationRegistration(
                        federationId, shard.getShardIndex(), UUID.randomUUID(), prefix + (seq++)));
            }
            shard.setFilledCount(shard.getFilledCount() + take);
            if (shard.getFilledCount() >= federation.getShardSize()) {
                shard.markReady();
            }
            remaining -= take;
            placed += take;
            touched.add(shard);
        }
        registrationRepository.saveAll(regs);
        shardRepository.saveAll(touched);
        // Restore the single-open-shard invariant for any later single registrations.
        if (openFillShard(federationId).isEmpty()) {
            openNextPendingShard(federationId);
        }
        log.info("Federation {} — batch-registered {} bot(s)", federationId, placed);
        return placed;
    }

    /**
     * Materialize READY shards into running child pyramid tournaments, up to the wave concurrency cap. Each
     * started shard gets a child PYRAMID tournament seeded with its players and started. Returns the number of
     * shards started in this call. Safe to call repeatedly (e.g. after a shard completes a slot frees up).
     */
    @Transactional
    public int promoteShards(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        int cap = tournamentProperties.getFederatedMaxConcurrentShards();
        // BUYUP_OPEN shards (the pre-start buy-out window) also occupy a wave slot.
        int running = shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING)
                + shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.BUYUP_OPEN);
        List<PyramidFederationShard> ready = shardRepository
                .findByFederationIdAndStatus(federationId, FederationShardStatus.READY).stream()
                .sorted((a, b) -> Integer.compare(a.getShardIndex(), b.getShardIndex()))
                .toList();

        int started = 0;
        for (PyramidFederationShard shard : ready) {
            if (running >= cap) {
                break;
            }
            if (federation.isBuyUpEnabled()) {
                materializeBuyUpShard(federation, shard);
            } else {
                startShard(federation, shard);
            }
            running++;
            started++;
        }
        if (started > 0 && federation.getStatus() == FederationStatus.REGISTERING) {
            federation.markShardsRunning();
            federationRepository.save(federation);
        }
        if (started > 0) {
            log.info("Federation {} — promoted {} shard(s) to RUNNING ({} now running)",
                    federationId, started, running);
        }
        return started;
    }

    /**
     * Run one RUNNING shard's pyramid to its single winner (reusing {@link PyramidTournamentService}, which
     * manages its own per-round transactions — so this is intentionally <b>not</b> wrapped in a transaction),
     * then record the winner, free the wave slot (promote the next READY shard) and, once every shard is
     * done, flip the federation to AWAITING_FINAL. Returns the pyramid run result (champion = the finalist).
     */
    public PyramidRunResult runShardToWinner(UUID federationId, UUID shardId) {
        PyramidFederationShard shard = shardRepository.findById(shardId)
                .orElseThrow(() -> new NoSuchElementException("Shard not found: " + shardId));
        if (shard.getStatus() != FederationShardStatus.RUNNING) {
            throw new IllegalStateException("Shard " + shardId + " is not RUNNING (" + shard.getStatus() + ")");
        }
        PyramidRunResult result = pyramidTournamentService.runToCompletion(shard.getTournamentId());
        self.recordShardWinner(federationId, shardId, result.championId());
        return result;
    }

    /** Record a shard's winner, promote the next wave into the freed slot, and await-final when all are done. */
    @Transactional
    public void recordShardWinner(UUID federationId, UUID shardId, UUID championId) {
        PyramidFederationShard shard = shardRepository.findById(shardId).orElseThrow();
        if (shard.getStatus() == FederationShardStatus.COMPLETED) {
            return; // idempotent
        }
        shard.completeWith(championId);
        shardRepository.save(shard);
        log.info("Federation {} — shard {} complete, winner {}", federationId, shard.getShardIndex(), championId);
        promoteShards(federationId);
        maybeAwaitFinal(federationId);
    }

    /**
     * Drive the whole shard phase to completion (for tests / a manual admin trigger): start the first wave,
     * then run every RUNNING shard to its winner — each completion auto-promotes the next READY shard — until
     * no shard is running. Returns the federation's resulting status (AWAITING_FINAL when all shards finished).
     */
    public FederationStatus drainShards(UUID federationId) {
        self.promoteShards(federationId);
        int guard = 0;
        while (guard++ < 100_000) {
            // Buy-up shards sit in BUYUP_OPEN until their window is closed; end it so they can run.
            self.closeBuyUpAndStart(federationId);
            List<PyramidFederationShard> running = shardRepository
                    .findByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING);
            if (running.isEmpty()) {
                break;
            }
            for (PyramidFederationShard shard : running) {
                runShardToWinner(federationId, shard.getId());
            }
        }
        return requireFederation(federationId).getStatus();
    }

    private void maybeAwaitFinal(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.SHARDS_RUNNING
                && federation.getStatus() != FederationStatus.REGISTERING) {
            return;
        }
        // The field is fully resolved when each shard either produced a winner or was closed by a final buy-out.
        int resolved = shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED)
                + finalBuyoutRepository.countByFederationId(federationId);
        if (resolved == federation.getShardCount()) {
            federation.markAwaitingFinal();
            federationRepository.save(federation);
            log.info("Federation {} — field resolved ({} shards); AWAITING_FINAL",
                    federationId, federation.getShardCount());
        }
    }

    /** A buyable final seat: claiming it closes the (empty) shard at {@code shardIndex} for {@code price}. */
    public record FinalSeatTicket(int shardIndex, BigDecimal price, CryptoAsset asset) {
    }

    /**
     * Buy-up variant: the final seats a player can buy directly (bypassing the shards). A seat is buyable while
     * its shard is still empty (no floor registrations) and not already bought; the price is a whole shard's
     * buy-ins ({@code shardSize × buyIn}).
     */
    @Transactional(readOnly = true)
    public List<FinalSeatTicket> availableFinalSeats(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isBuyUpEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not a buy-up federation");
        }
        BigDecimal price = finalSeatPrice(federation);
        List<FinalSeatTicket> tickets = new ArrayList<>();
        for (PyramidFederationShard shard : shardRepository.findByFederationIdOrderByShardIndexAsc(federationId)) {
            if (isFinalSeatBuyable(federationId, shard)) {
                tickets.add(new FinalSeatTicket(shard.getShardIndex(), price, federation.getCryptoBuyInAsset()));
            }
        }
        return tickets;
    }

    /**
     * Buy-up variant: buy a guaranteed seat among the finalists, bypassing the shards. Claims (and closes) the
     * empty shard at {@code shardIndex} — the buyer becomes its finalist. Charges {@code shardSize × buyIn}.
     * One buy-out per player. When the closed shards plus completed shards account for the whole field, the
     * federation moves to AWAITING_FINAL.
     */
    @Transactional
    public PyramidFederationFinalBuyout buyFinalSeat(UUID federationId, UUID playerId, int shardIndex) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isBuyUpEnabled() || !federation.isRealMoney()) {
            throw new IllegalStateException("Federation " + federationId + " is not a real-money buy-up federation");
        }
        if (federation.getStatus() != FederationStatus.REGISTERING
                && federation.getStatus() != FederationStatus.SHARDS_RUNNING) {
            throw new IllegalStateException("Final seats can only be bought before the final");
        }
        if (finalBuyoutRepository.existsByFederationIdAndBuyerPlayerId(federationId, playerId)) {
            throw new IllegalStateException("Player has already bought a final seat");
        }
        PyramidFederationShard shard = shardRepository.findByFederationIdAndShardIndex(federationId, shardIndex)
                .orElseThrow(() -> new NoSuchElementException("Shard " + shardIndex + " not found"));
        if (!isFinalSeatBuyable(federationId, shard)) {
            throw new IllegalStateException("Final seat not buyable (shard is not empty, or already taken)");
        }
        BigDecimal price = finalSeatPrice(federation);
        walletService.chargeBuyIn(playerId, federation.getCryptoBuyInAsset(), price,
                "fedfinalbuy:" + federationId + ":" + playerId);
        boolean wasOpenForFill = shard.getStatus() == FederationShardStatus.REGISTERING;
        shard.cancel(); // closed — its finalist slot is the buyer
        shardRepository.save(shard);
        if (wasOpenForFill) {
            openNextPendingShard(federationId);
        }
        PyramidFederationFinalBuyout buyout = finalBuyoutRepository.save(new PyramidFederationFinalBuyout(
                federationId, playerId, shardIndex, price, federation.getCryptoBuyInAsset()));
        log.info("Player {} bought a final seat (closing shard {}) in federation {} for {} {}",
                playerId, shardIndex, federationId, price, federation.getCryptoBuyInAsset());
        maybeAwaitFinal(federationId);
        return buyout;
    }

    private boolean isFinalSeatBuyable(UUID federationId, PyramidFederationShard shard) {
        return shard.getFilledCount() == 0
                && (shard.getStatus() == FederationShardStatus.PENDING
                        || shard.getStatus() == FederationShardStatus.REGISTERING)
                && !finalBuyoutRepository.existsByFederationIdAndShardIndex(federationId, shard.getShardIndex());
    }

    private static BigDecimal finalSeatPrice(PyramidFederation federation) {
        return federation.getCryptoBuyInAmount().multiply(BigDecimal.valueOf(federation.getShardSize()));
    }

    /**
     * Admin action: once all shards are done (AWAITING_FINAL), set the final's start time and e-mail the
     * finalists (the shard winners). The time may be any future instant. Moves the federation to
     * FINAL_SCHEDULED. Returns the count of finalists e-mailed (0 for finalists with no owning user).
     */
    @Transactional
    public int scheduleFinal(UUID federationId, Instant when) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.AWAITING_FINAL) {
            throw new IllegalStateException(
                    "Final can only be scheduled once all shards are done (status " + federation.getStatus() + ")");
        }
        if (when == null || !when.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Final start time must be in the future");
        }
        federation.scheduleFinal(when);
        federationRepository.save(federation);
        List<UUID> finalists = finalistPlayerIds(federationId);
        int notified = notificationService.notifyFederationFinalScheduled(
                federation.getName(), finalists, when);
        log.info("Federation {} — final scheduled for {} ({} finalists, {} e-mailed)",
                federationId, when, finalists.size(), notified);
        return notified;
    }

    /**
     * Create and seed the final pyramid from the shard winners and start it (mirrors a shard start). Allowed
     * from FINAL_SCHEDULED (the admin's slot) or directly from AWAITING_FINAL. Moves the federation to
     * FINAL_RUNNING; running it to the grand champion is the next slice.
     */
    @Transactional
    public PyramidFederation startFinal(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.FINAL_SCHEDULED
                && federation.getStatus() != FederationStatus.AWAITING_FINAL) {
            throw new IllegalStateException("Final cannot start from status " + federation.getStatus());
        }
        Tournament finalT = tournamentService.createTournament(CreateTournamentRequest.pyramid(
                federation.getName() + " — final", federation.getShardCount(),
                federation.getSeatsPerTable(), federation.getHandsPerRound()));
        for (PyramidFederationShard shard :
                shardRepository.findByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED)) {
            UUID winner = shard.getWinnerPlayerId();
            if (winner == null) {
                continue;
            }
            String name = registrationRepository.findByFederationIdAndPlayerId(federationId, winner)
                    .map(PyramidFederationRegistration::getPlayerName).orElse("Finalist");
            tournamentService.registerPlayer(finalT.getId(), winner, name);
        }
        // Buy-up variant: the players who bought a final seat are finalists too (they closed an empty shard).
        for (PyramidFederationFinalBuyout buyout : finalBuyoutRepository.findByFederationId(federationId)) {
            tournamentService.registerPlayer(finalT.getId(), buyout.getBuyerPlayerId(),
                    "Finalist-" + buyout.getShardIndex());
        }
        tournamentService.startTournament(finalT.getId());
        federation.markFinalRunning(finalT.getId());
        federationRepository.save(federation);
        log.info("Federation {} — final tournament {} created + started", federationId, finalT.getId());
        return federation;
    }

    /**
     * Run the final pyramid to the grand champion (reusing {@link PyramidTournamentService}, so — like a shard
     * run — this is intentionally not wrapped in a transaction), then record the champion and complete the
     * federation. Requires FINAL_RUNNING. Returns the final's pyramid run result.
     */
    public PyramidRunResult runFinalToChampion(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.FINAL_RUNNING) {
            throw new IllegalStateException("Final is not running (status " + federation.getStatus() + ")");
        }
        PyramidRunResult result = pyramidTournamentService.runToCompletion(federation.getFinalTournamentId());
        self.recordChampion(federationId, result.championId());
        return result;
    }

    /** Record the grand champion and mark the federation COMPLETED (idempotent). */
    @Transactional
    public void recordChampion(UUID federationId, UUID championPlayerId) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() == FederationStatus.COMPLETED) {
            return;
        }
        federation.complete(championPlayerId);
        federationRepository.save(federation);
        log.info("Federation {} — COMPLETED, grand champion {}", federationId, championPlayerId);
        // Buy-up federations have a mixed pool (buy-ins + buy-out prices); their payout reconciliation is a
        // later slice ("money later"). Plain real-money federations pay out the flat pool here.
        if (federation.isRealMoney() && !federation.isBuyUpEnabled()) {
            distributePrizes(federation, championPlayerId);
        }
    }

    /**
     * Admin action (buy-up federations): distribute the prize pool once the federation is COMPLETED. The pool
     * is the <b>expected buy-ins</b> — a guaranteed pool of the full field ({@code shardCount × shardSize ×
     * buyIn}), independent of actual fill / buy-out prices — paid out across the final table by finish position
     * (see {@link #payPool}). Idempotent.
     */
    @Transactional
    public void distributeFederationPrizes(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() != FederationStatus.COMPLETED || federation.getChampionPlayerId() == null) {
            throw new IllegalStateException("Federation " + federationId + " is not completed");
        }
        if (!federation.isRealMoney()) {
            throw new IllegalStateException("Play-money federation has no prize pool");
        }
        payPool(federation, federation.getChampionPlayerId(), expectedPool(federation));
    }

    /** The guaranteed prize pool from the expected buy-ins: the full field × the buy-in. */
    private static BigDecimal expectedPool(PyramidFederation federation) {
        return federation.getCryptoBuyInAmount()
                .multiply(BigDecimal.valueOf((long) federation.getShardCount() * federation.getShardSize()));
    }

    /**
     * Admin action: tune the federation's prize config (shard-winner qualifier ppm + the non-champion
     * final-table place/rest shares) before the prizes are paid out. Validated so the field shares plus the
     * shard qualifiers never exceed the pool (the champion always takes a non-negative remainder). Rejected once
     * the federation is COMPLETED or CANCELLED (the payout has run / cannot run).
     */
    @Transactional
    public void updateFederationPrizeConfig(UUID federationId, Integer shardWinnerPpm,
            String finalTablePlaceBps, Integer finalTableRestBps) {
        PyramidFederation federation = requireFederation(federationId);
        if (federation.getStatus() == FederationStatus.COMPLETED
                || federation.getStatus() == FederationStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Prize config cannot be changed once the federation is " + federation.getStatus());
        }
        int ppm = shardWinnerPpm != null ? shardWinnerPpm : tournamentProperties.getFederatedShardWinnerPpm();
        int restBps = finalTableRestBps != null ? finalTableRestBps : tournamentProperties.getFederatedFinalTableRestBps();
        List<Integer> placeBps = parsePlaceBps(finalTablePlaceBps);
        if (ppm < 0 || restBps < 0 || placeBps.stream().anyMatch(b -> b < 0)) {
            throw new IllegalArgumentException("Prize shares must be non-negative");
        }
        // The shard qualifiers consume ppm/1e6 of the pool each (×shardCount); convert to bps (ceil) and require
        // the final-table shares + qualifiers to fit in 100%, so the champion's remainder is never negative.
        long qualifierBps = ((long) ppm * federation.getShardCount() + 99) / 100;
        long total = placeBps.stream().mapToLong(Integer::longValue).sum() + restBps + qualifierBps;
        if (total > 10_000) {
            throw new IllegalArgumentException(
                    "Final-table shares + shard qualifiers exceed 100% of the pool (" + total + " bps)");
        }
        federation.setShardWinnerPpm(ppm);
        federation.setFinalTablePlaceBps(csv(placeBps));
        federation.setFinalTableRestBps(restBps);
        federationRepository.save(federation);
        log.info("Federation {} — prize config updated (ppm={}, placeBps={}, restBps={})",
                federationId, ppm, placeBps, restBps);
    }

    /** Parse a comma-separated bps list (blank/null = the global default place schedule). */
    private List<Integer> parsePlaceBps(String csv) {
        if (csv == null || csv.isBlank()) {
            return tournamentProperties.getFederatedFinalTablePlaceBps();
        }
        List<Integer> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                try {
                    out.add(Integer.parseInt(t));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid basis-points value: '" + t + "'");
                }
            }
        }
        return out;
    }

    /** Render an integer list as a comma-separated string (empty list → empty string). */
    private static String csv(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private void distributePrizes(PyramidFederation federation, UUID championId) {
        long registered = registrationRepository.countByFederationId(federation.getId());
        payPool(federation, championId,
                federation.getCryptoBuyInAmount().multiply(BigDecimal.valueOf(registered)));
    }

    /**
     * Pay out the net pool ({@code grossPool} less the organisation fee, ≤20%) with two combined logics, summing
     * to exactly 100% of the net pool:
     * <ol>
     *   <li><b>Shard-winner qualifier</b> — each shard winner gets {@code federatedShardWinnerPpm} ppm of the
     *       net pool (e.g. 1 ppm = 0.0001%).</li>
     *   <li><b>Final-table places</b> — the non-champion final-table places split
     *       {@code federatedFinalTablePlaceBps} (2nd, 3rd, …) + {@code federatedFinalTableRestBps} among the
     *       rest (see {@link FederatedPrizeSplit}).</li>
     * </ol>
     * The grand champion takes the whole remainder (net − qualifiers − non-champion places), so the payouts sum
     * exactly to the net pool and the champion absorbs all rounding. A final-table player who is also a shard
     * winner collects both. When no final table is on record, only the qualifiers apply and the champion takes
     * the rest. Idempotent via per-recipient award keys.
     */
    private void payPool(PyramidFederation federation, UUID championId, BigDecimal grossPool) {
        UUID federationId = federation.getId();
        CryptoAsset asset = federation.getCryptoBuyInAsset();
        // Organisation fee off the top (≤20%): the field splits the net pool, the fee is withheld (recorded as
        // house revenue). With a 0% fee net == gross.
        BigDecimal houseFee = federation.houseFeeOn(grossPool);
        BigDecimal pool = grossPool.subtract(houseFee);
        recordHouseFee(TournamentFeeEntry.SourceType.FEDERATION, federationId, asset, grossPool, houseFee,
                federation.getFeeBasisPoints(), "fedfee:" + federationId);
        BigDecimal paidOut = BigDecimal.ZERO;

        // Per-federation prize config (snapshot at creation, admin-editable), falling back to the global default.
        int ppm = federation.getShardWinnerPpm() != null
                ? federation.getShardWinnerPpm() : tournamentProperties.getFederatedShardWinnerPpm();
        List<Integer> placeBps = federation.finalTablePlaceBpsList() != null
                ? federation.finalTablePlaceBpsList() : tournamentProperties.getFederatedFinalTablePlaceBps();
        int restBps = federation.getFinalTableRestBps() != null
                ? federation.getFinalTableRestBps() : tournamentProperties.getFederatedFinalTableRestBps();

        // 1) Shard-winner qualifier — a flat ppm of the net pool to every shard winner.
        BigDecimal perWinner = pool.multiply(BigDecimal.valueOf(ppm))
                .divide(BigDecimal.valueOf(1_000_000), 18, RoundingMode.DOWN);
        List<PyramidFederationShard> completed =
                shardRepository.findByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED);
        for (PyramidFederationShard shard : completed) {
            UUID winner = shard.getWinnerPlayerId();
            if (winner == null || perWinner.signum() <= 0) {
                continue;
            }
            walletService.awardPayout(winner, asset, perWinner, "fedqual:" + federationId + ":" + shard.getShardIndex());
            paidOut = paidOut.add(perWinner);
        }

        // 2) Final-table places — the non-champion seats (2nd, 3rd, …) of the final tournament, by finish order.
        List<TournamentRegistration> finalTable = federation.getFinalTournamentId() == null
                ? List.of()
                : tournamentRegistrationRepository.findTopFinishersByTournament(
                        federation.getFinalTournamentId(), federation.getSeatsPerTable());
        if (!finalTable.isEmpty()) {
            List<BigDecimal> placeAwards = FederatedPrizeSplit.nonChampionAwards(pool, finalTable.size(),
                    placeBps, restBps);
            for (int i = 1; i < finalTable.size(); i++) { // i = finish position − 1; skip the champion (i == 0)
                UUID playerId = finalTable.get(i).getPlayerId();
                BigDecimal award = placeAwards.get(i - 1);
                if (playerId == null || award.signum() <= 0) {
                    continue;
                }
                walletService.awardPayout(playerId, asset, award, "fedplace:" + federationId + ":" + (i + 1));
                paidOut = paidOut.add(award);
            }
        }

        // 3) Champion takes the remainder (absorbs all rounding), so the payouts sum exactly to the net pool.
        BigDecimal championPrize = pool.subtract(paidOut);
        if (championId != null && championPrize.signum() > 0) {
            walletService.awardPayout(championId, asset, championPrize, "fedchamp:" + federationId);
        }
        log.info("Federation {} — paid net pool {} {} (gross {}, house fee {}; {} ×{} shard qualifiers, {} to other places, {} to champion)",
                federationId, pool, asset, grossPool, houseFee, perWinner, completed.size(),
                paidOut.subtract(perWinner.multiply(BigDecimal.valueOf(completed.size()))), championPrize);
    }

    /** Record a withheld house commission as revenue (idempotent on {@code key}; no-op for a zero fee). */
    private void recordHouseFee(TournamentFeeEntry.SourceType sourceType, UUID sourceId, CryptoAsset asset,
            BigDecimal grossPool, BigDecimal fee, int feeBasisPoints, String key) {
        if (fee == null || fee.signum() <= 0 || feeEntryRepository.existsByIdempotencyKey(key)) {
            return;
        }
        feeEntryRepository.save(new TournamentFeeEntry(
                sourceType, sourceId, asset, grossPool, fee, feeBasisPoints, key));
        log.info("Federation {} — recorded house fee {} {} ({} bps of {})",
                sourceId, fee, asset, feeBasisPoints, grossPool);
    }

    /** Read view of a federation: config, status, per-shard-status counts, champion. */
    @Transactional(readOnly = true)
    public FederationDetailResponse getFederationDetail(UUID federationId) {
        PyramidFederation f = requireFederation(federationId);
        return new FederationDetailResponse(
                f.getId(), f.getName(), f.getStatus(), f.getShardSize(), f.getShardCount(),
                f.getSeatsPerTable(), registrationRepository.countByFederationId(federationId),
                f.getRegistrationDeadline(), f.getFinalScheduledStart(), f.getFinalTournamentId(),
                f.getChampionPlayerId(), f.getFeeBasisPoints(),
                shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.PENDING),
                shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.REGISTERING),
                shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.READY),
                shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING),
                shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED),
                f.getShardWinnerPpm() != null ? f.getShardWinnerPpm()
                        : tournamentProperties.getFederatedShardWinnerPpm(),
                f.getFinalTablePlaceBps() != null ? f.getFinalTablePlaceBps()
                        : csv(tournamentProperties.getFederatedFinalTablePlaceBps()),
                f.getFinalTableRestBps() != null ? f.getFinalTableRestBps()
                        : tournamentProperties.getFederatedFinalTableRestBps(),
                f.getCryptoBuyInAmount(), f.getCryptoBuyInAsset(), f.isIsolatedWalletsEnabled());
    }

    /** The finalists to notify: shard winners plus anyone who bought a final seat directly. */
    private List<UUID> finalistPlayerIds(UUID federationId) {
        List<UUID> finalists = new ArrayList<>(
                shardRepository.findByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED).stream()
                        .sorted((a, b) -> Integer.compare(a.getShardIndex(), b.getShardIndex()))
                        .map(PyramidFederationShard::getWinnerPlayerId)
                        .filter(w -> w != null)
                        .toList());
        for (PyramidFederationFinalBuyout buyout : finalBuyoutRepository.findByFederationId(federationId)) {
            finalists.add(buyout.getBuyerPlayerId());
        }
        return finalists;
    }

    /**
     * Buy-up variant: materialize a shard into a real-money buy-up child pyramid and seat its players (charging
     * the buy-in via the wallet bridge), but leave it REGISTERING so players can buy guaranteed higher-level
     * seats through the existing buy-up endpoints on {@code shard.tournamentId}. The shard becomes BUYUP_OPEN;
     * {@link #closeBuyUpAndStart} ends the window and starts it.
     */
    private void materializeBuyUpShard(PyramidFederation federation, PyramidFederationShard shard) {
        Tournament child = tournamentService.createTournament(CreateTournamentRequest.pyramid(
                federation.getName() + " — shard " + shard.getShardIndex(),
                federation.getShardSize(), federation.getSeatsPerTable(), federation.getHandsPerRound()));
        // The tournament is managed in this transaction (createTournament joined it) — mutations flush on
        // commit / before the bridge's lookup, so no explicit save is needed (and a merge would choke on the
        // entity's immutable collections).
        child.setCryptoBuyInAmount(federation.getCryptoBuyInAmount());
        child.setCryptoBuyInAsset(federation.getCryptoBuyInAsset());
        child.setFeeBasisPoints(federation.getFeeBasisPoints());
        child.setPyramidBuyUpEnabled(true);
        for (PyramidFederationRegistration reg :
                registrationRepository.findByFederationIdAndShardIndex(federation.getId(), shard.getShardIndex())) {
            // Charges the buy-in + registers into the child (so a later buy-out can replace that buy-in).
            tournamentWalletService.buyIn(reg.getPlayerId(), child.getId(), reg.getPlayerName());
        }
        shard.markBuyUpOpen(child.getId());
        shardRepository.save(shard);
    }

    /**
     * Buy-up variant: close the buy-out window of every BUYUP_OPEN shard and start it (fixed-bracket seating
     * honours the buy-outs), moving it to RUNNING. Idempotent. Returns the number started.
     */
    @Transactional
    public int closeBuyUpAndStart(UUID federationId) {
        int started = 0;
        for (PyramidFederationShard shard :
                shardRepository.findByFederationIdAndStatus(federationId, FederationShardStatus.BUYUP_OPEN)) {
            tournamentService.startTournament(shard.getTournamentId());
            shard.markRunning(shard.getTournamentId());
            shardRepository.save(shard);
            started++;
        }
        if (started > 0) {
            log.info("Federation {} — closed buy-out window + started {} shard(s)", federationId, started);
        }
        return started;
    }

    /**
     * Buy-up variant: close a shard's registration early and open its buy-out window, even if it is not full —
     * which is exactly what makes higher-level seats buyable (their sub-pyramids are above the floor frontier
     * only while the shard is under-filled). The shard moves to BUYUP_OPEN; players then buy seats via the
     * buy-up endpoints on the returned shard's tournament, and {@link #closeBuyUpAndStart} starts it.
     */
    @Transactional
    public PyramidFederationShard openShardForBuyUp(UUID federationId, int shardIndex) {
        PyramidFederation federation = requireFederation(federationId);
        if (!federation.isBuyUpEnabled()) {
            throw new IllegalStateException("Federation " + federationId + " is not a buy-up federation");
        }
        PyramidFederationShard shard = shardRepository.findByFederationIdAndShardIndex(federationId, shardIndex)
                .orElseThrow(() -> new NoSuchElementException("Shard " + shardIndex + " not found"));
        if (shard.getStatus() != FederationShardStatus.REGISTERING
                && shard.getStatus() != FederationShardStatus.READY) {
            throw new IllegalStateException("Shard " + shardIndex + " cannot open for buy-up (" + shard.getStatus() + ")");
        }
        materializeBuyUpShard(federation, shard);
        if (federation.getStatus() == FederationStatus.REGISTERING) {
            federation.markShardsRunning();
            federationRepository.save(federation);
        }
        return shardRepository.findByFederationIdAndShardIndex(federationId, shardIndex).orElseThrow();
    }

    private void startShard(PyramidFederation federation, PyramidFederationShard shard) {
        Tournament child = tournamentService.createTournament(CreateTournamentRequest.pyramid(
                federation.getName() + " — shard " + shard.getShardIndex(),
                federation.getShardSize(), federation.getSeatsPerTable(), federation.getHandsPerRound()));
        for (PyramidFederationRegistration reg :
                registrationRepository.findByFederationIdAndShardIndex(federation.getId(), shard.getShardIndex())) {
            tournamentService.registerPlayer(child.getId(), reg.getPlayerId(), reg.getPlayerName());
        }
        tournamentService.startTournament(child.getId());
        shard.markRunning(child.getId());
        shardRepository.save(shard);
    }

    private java.util.Optional<PyramidFederationShard> openFillShard(UUID federationId) {
        return shardRepository
                .findByFederationIdAndStatus(federationId, FederationShardStatus.REGISTERING).stream()
                .min((a, b) -> Integer.compare(a.getShardIndex(), b.getShardIndex()));
    }

    private void openNextPendingShard(UUID federationId) {
        shardRepository.findByFederationIdAndStatus(federationId, FederationShardStatus.PENDING).stream()
                .min((a, b) -> Integer.compare(a.getShardIndex(), b.getShardIndex()))
                .ifPresent(next -> {
                    next.markRegistering();
                    shardRepository.save(next);
                });
    }

    private PyramidFederationShard shardForExistingPlayer(UUID federationId, UUID playerId) {
        int shardIndex = registrationRepository.findByFederationIdAndPlayerId(federationId, playerId)
                .orElseThrow().getShardIndex();
        return shardRepository.findByFederationIdAndShardIndex(federationId, shardIndex).orElseThrow();
    }

    private PyramidFederation requireFederation(UUID federationId) {
        return federationRepository.findById(federationId)
                .orElseThrow(() -> new NoSuchElementException("Federation not found: " + federationId));
    }
}
