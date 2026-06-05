package com.truholdem.service.tournament;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationRegistration;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.model.Tournament;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.PyramidTournamentService.PyramidRunResult;

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
    private final TournamentService tournamentService;
    private final PyramidTournamentService pyramidTournamentService;
    private final AppProperties.Tournament tournamentProperties;
    /** Self proxy, so {@link #recordShardWinner} runs in its own transaction when called internally. */
    private final FederatedPyramidService self;

    public FederatedPyramidService(PyramidFederationRepository federationRepository,
            PyramidFederationShardRepository shardRepository,
            PyramidFederationRegistrationRepository registrationRepository,
            TournamentService tournamentService, PyramidTournamentService pyramidTournamentService,
            AppProperties appProperties, @Lazy FederatedPyramidService self) {
        this.federationRepository = federationRepository;
        this.shardRepository = shardRepository;
        this.registrationRepository = registrationRepository;
        this.tournamentService = tournamentService;
        this.pyramidTournamentService = pyramidTournamentService;
        this.tournamentProperties = appProperties.getTournament();
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
        int seats = tournamentProperties.getPyramidDefaultSeatsPerTable();
        int hands = tournamentProperties.getPyramidDefaultHandsPerRound();
        FederatedPyramidPlan plan = new FederatedPyramidPlan(startingPlayers, shardSize, seats);

        PyramidFederation federation = federationRepository.save(new PyramidFederation(
                name, shardSize, plan.shardCount(), seats, hands, registrationDeadline));

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
        if (registrationRepository.existsByFederationIdAndPlayerId(federationId, playerId)) {
            return shardForExistingPlayer(federationId, playerId);
        }
        PyramidFederationShard shard = openFillShard(federationId)
                .orElseThrow(() -> new IllegalStateException("Federation is full — all shards are filled"));

        registrationRepository.save(
                new PyramidFederationRegistration(federationId, shard.getShardIndex(), playerId, playerName));
        shard.incrementFilled();
        if (shard.getFilledCount() >= federation.getShardSize()) {
            shard.markReady();
            openNextPendingShard(federationId);
        }
        return shardRepository.save(shard);
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
        int running = shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING);
        List<PyramidFederationShard> ready = shardRepository
                .findByFederationIdAndStatus(federationId, FederationShardStatus.READY).stream()
                .sorted((a, b) -> Integer.compare(a.getShardIndex(), b.getShardIndex()))
                .toList();

        int started = 0;
        for (PyramidFederationShard shard : ready) {
            if (running >= cap) {
                break;
            }
            startShard(federation, shard);
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
        if (federation.getStatus() == FederationStatus.SHARDS_RUNNING
                && shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED)
                        == federation.getShardCount()) {
            federation.markAwaitingFinal();
            federationRepository.save(federation);
            log.info("Federation {} — all {} shards done; AWAITING_FINAL", federationId, federation.getShardCount());
        }
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
