package com.truholdem.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.AppProperties;
import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.CreateFederationRequest;
import com.truholdem.dto.FederationDetailResponse;
import com.truholdem.dto.FederationRefundResponse;
import com.truholdem.dto.FederationWalletImportRequest;
import com.truholdem.dto.PrizeConfigRequest;
import com.truholdem.dto.RefundApprovalRequest;
import com.truholdem.dto.ScheduleTournamentRequest;
import com.truholdem.dto.wallet.AtaBatchConfirmRequest;
import com.truholdem.dto.wallet.AtaCloseRequest;
import com.truholdem.dto.wallet.RejectWithdrawalRequest;
import com.truholdem.dto.wallet.SolAtaBatchUnsignedDto;
import com.truholdem.dto.wallet.SolBroadcastRequest;
import com.truholdem.dto.wallet.SolRefundUnsignedDto;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.User;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.tournament.FederationRefundService;
import com.truholdem.service.wallet.sol.SolAtaProvisioner;
import com.truholdem.service.wallet.sol.SolRefundCoordinator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin control plane for federated (sharded) pyramids: create one, watch its shard/finalist progress, and
 * drive the lifecycle (promote waves, schedule + start + run the final). Gated by
 * {@code app.tournament.federated-pyramid-enabled}.
 */
@RestController
@ApiV1Config
@RequestMapping("/admin/pyramid-federations")
@Tag(name = "Admin Federated Pyramids", description = "Sharded pyramid orchestration (ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPyramidFederationController {

    private static final Logger log = LoggerFactory.getLogger(AdminPyramidFederationController.class);

    private final FederatedPyramidService federatedService;
    private final FederationRefundService refundService;
    private final ObjectProvider<SolRefundCoordinator> refundCoordinator;
    private final ObjectProvider<SolAtaProvisioner> ataProvisioner;
    private final AppProperties appProperties;

    public AdminPyramidFederationController(FederatedPyramidService federatedService,
            FederationRefundService refundService,
            ObjectProvider<SolRefundCoordinator> refundCoordinator,
            ObjectProvider<SolAtaProvisioner> ataProvisioner, AppProperties appProperties) {
        this.federatedService = federatedService;
        this.refundService = refundService;
        this.refundCoordinator = refundCoordinator;
        this.ataProvisioner = ataProvisioner;
        this.appProperties = appProperties;
    }

    @PostMapping
    @Operation(summary = "Create a federated pyramid (field split into shards of shardSize)")
    public ResponseEntity<FederationDetailResponse> create(@Valid @RequestBody CreateFederationRequest request) {
        assertEnabled();
        PyramidFederation fed = federatedService.createFederation(
                request.name(), request.startingPlayers(), request.shardSize(),
                request.registrationDeadline(), request.buyInAmount(), request.buyInAsset(),
                request.buyUpEnabled(),
                request.feeBasisPoints() == null ? 0 : request.feeBasisPoints(),
                request.isolatedWalletsEnabled());
        log.info("Admin created federated pyramid {} ({} players / shard {})",
                fed.getId(), request.startingPlayers(), request.shardSize());
        return ResponseEntity.status(HttpStatus.CREATED).body(federatedService.getFederationDetail(fed.getId()));
    }

    @PostMapping("/{id}/import-wallets")
    @Operation(summary = "Isolated custody: import offline-generated dedicated per-player wallets (USDT ATAs)")
    public ResponseEntity<java.util.Map<String, Integer>> importWallets(@PathVariable UUID id,
            @Valid @RequestBody FederationWalletImportRequest request) {
        assertEnabled();
        int imported = federatedService.importPlayerWallets(id, request.wallets());
        log.info("Admin imported {} dedicated wallet(s) into federation {}", imported, id);
        return ResponseEntity.ok(java.util.Map.of("imported", imported));
    }

    @PostMapping("/{id}/reconcile-deposits")
    @Operation(summary = "Isolated custody: poll dedicated wallets on-chain and seat players whose buy-in landed")
    public ResponseEntity<java.util.Map<String, Integer>> reconcileDeposits(@PathVariable UUID id) {
        assertEnabled();
        int seated = federatedService.reconcileDeposits(id);
        return ResponseEntity.ok(java.util.Map.of("seated", seated));
    }

    @PostMapping("/{id}/release-no-shows")
    @Operation(summary = "Isolated custody: release assigned-but-unfunded wallets (no-shows) past the deposit window")
    public ResponseEntity<java.util.Map<String, Integer>> releaseNoShows(@PathVariable UUID id) {
        assertEnabled();
        int released = federatedService.releaseNoShows(id);
        return ResponseEntity.ok(java.util.Map.of("released", released));
    }

    // --- Isolated-custody refunds (admin-approved) ---

    @PostMapping("/{id}/players/{playerId}/refund")
    @Operation(summary = "Request a refund of a player's funded buy-in (PENDING_APPROVAL)")
    public ResponseEntity<FederationRefundResponse> requestRefund(@PathVariable UUID id,
            @PathVariable UUID playerId) {
        assertEnabled();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FederationRefundResponse.from(refundService.requestRefund(id, playerId)));
    }

    @PostMapping("/{id}/refunds/request")
    @Operation(summary = "Request refunds for all funded wallets (federation cancelled / under-filled)")
    public ResponseEntity<java.util.Map<String, Integer>> requestRefunds(@PathVariable UUID id) {
        assertEnabled();
        return ResponseEntity.ok(java.util.Map.of("requested", refundService.requestRefundsForCancelled(id)));
    }

    @PostMapping("/refunds/{refundId}/approve")
    @Operation(summary = "Approve a refund and set the player's destination address (→ APPROVED)")
    public ResponseEntity<FederationRefundResponse> approveRefund(@PathVariable UUID refundId,
            @Valid @RequestBody RefundApprovalRequest body, @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        UUID moderatorId = ((User) principal).getId();
        return ResponseEntity.ok(
                FederationRefundResponse.from(refundService.approveRefund(refundId, moderatorId, body.toAddress())));
    }

    @PostMapping("/refunds/{refundId}/reject")
    @Operation(summary = "Reject a pending refund (→ REJECTED)")
    public ResponseEntity<FederationRefundResponse> rejectRefund(@PathVariable UUID refundId,
            @RequestBody(required = false) RejectWithdrawalRequest body,
            @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        UUID moderatorId = ((User) principal).getId();
        String reason = body == null ? null : body.reason();
        return ResponseEntity.ok(
                FederationRefundResponse.from(refundService.rejectRefund(refundId, moderatorId, reason)));
    }

    @GetMapping("/refunds/{refundId}/unsigned")
    @Operation(summary = "Assemble the unsigned refund tx (dedicated wallet → player) for the offline signer")
    public ResponseEntity<SolRefundUnsignedDto> refundUnsigned(@PathVariable UUID refundId) {
        assertEnabled();
        return ResponseEntity.ok(refundCoordinatorOrThrow().buildUnsigned(refundId));
    }

    @PostMapping("/refunds/{refundId}/broadcast")
    @Operation(summary = "Broadcast the offline-signed refund tx and record its signature (→ BROADCAST)")
    public ResponseEntity<FederationRefundResponse> refundBroadcast(@PathVariable UUID refundId,
            @Valid @RequestBody SolBroadcastRequest body) {
        assertEnabled();
        return ResponseEntity.ok(
                FederationRefundResponse.from(refundCoordinatorOrThrow().broadcast(refundId, body.signedTx())));
    }

    @PostMapping("/refunds/{refundId}/reconcile")
    @Operation(summary = "Reconcile a broadcast refund against its signature status (→ CONFIRMED)")
    public ResponseEntity<FederationRefundResponse> refundReconcile(@PathVariable UUID refundId) {
        assertEnabled();
        return ResponseEntity.ok(FederationRefundResponse.from(refundCoordinatorOrThrow().reconcile(refundId)));
    }

    private SolRefundCoordinator refundCoordinatorOrThrow() {
        SolRefundCoordinator coordinator = refundCoordinator.getIfAvailable();
        if (coordinator == null) {
            throw new IllegalStateException("Solana refund coordinator is disabled (app.payments.sol-rpc-enabled)");
        }
        return coordinator;
    }

    // --- Isolated-custody dedicated-wallet ATA lifecycle (offline-signed batches) ---

    @PostMapping("/{id}/ata/create/unsigned")
    @Operation(summary = "Assemble an unsigned batch that pre-creates dedicated wallets' USDT ATAs (operator pays "
            + "rent) — required before exchange buy-ins can land")
    public ResponseEntity<SolAtaBatchUnsignedDto> ataCreateUnsigned(@PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        assertEnabled();
        int batch = limit != null ? limit
                : appProperties.getTournament().getFederatedIsolatedAtaBatchSize();
        return ResponseEntity.ok(ataProvisionerOrThrow().buildCreateBatch(id, batch));
    }

    @PostMapping("/{id}/ata/close/unsigned")
    @Operation(summary = "Assemble an unsigned batch that closes finished wallets' (empty) ATAs to reclaim rent "
            + "to the operator")
    public ResponseEntity<SolAtaBatchUnsignedDto> ataCloseUnsigned(@PathVariable UUID id,
            @Valid @RequestBody AtaCloseRequest body) {
        assertEnabled();
        return ResponseEntity.ok(ataProvisionerOrThrow().buildCloseBatch(id, body.walletIds()));
    }

    @PostMapping("/{id}/ata/broadcast")
    @Operation(summary = "Broadcast an offline-signed ATA batch (create or close); returns the signature")
    public ResponseEntity<java.util.Map<String, String>> ataBroadcast(@PathVariable UUID id,
            @Valid @RequestBody SolBroadcastRequest body) {
        assertEnabled();
        return ResponseEntity.ok(java.util.Map.of("signature", ataProvisionerOrThrow().broadcast(body.signedTx())));
    }

    @PostMapping("/{id}/ata/create/confirm")
    @Operation(summary = "Confirm a broadcast create batch and mark its wallets ATA-provisioned")
    public ResponseEntity<java.util.Map<String, Integer>> ataConfirmCreated(@PathVariable UUID id,
            @Valid @RequestBody AtaBatchConfirmRequest body) {
        assertEnabled();
        return ResponseEntity.ok(java.util.Map.of("provisioned",
                ataProvisionerOrThrow().confirmCreated(id, body.walletIds(), body.signature())));
    }

    @PostMapping("/{id}/ata/close/confirm")
    @Operation(summary = "Confirm a broadcast close batch and mark its wallets ATA-closed")
    public ResponseEntity<java.util.Map<String, Integer>> ataConfirmClosed(@PathVariable UUID id,
            @Valid @RequestBody AtaBatchConfirmRequest body) {
        assertEnabled();
        return ResponseEntity.ok(java.util.Map.of("closed",
                ataProvisionerOrThrow().confirmClosed(id, body.walletIds(), body.signature())));
    }

    private SolAtaProvisioner ataProvisionerOrThrow() {
        SolAtaProvisioner provisioner = ataProvisioner.getIfAvailable();
        if (provisioner == null) {
            throw new IllegalStateException("Solana ATA provisioner is disabled (app.payments.sol-rpc-enabled)");
        }
        return provisioner;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Federation detail: status, per-shard-status counts, champion")
    public ResponseEntity<FederationDetailResponse> get(@PathVariable UUID id) {
        assertEnabled();
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/register-bots")
    @Operation(summary = "Bulk-register synthetic bot players (play-money load tests / simulation)")
    public ResponseEntity<FederationDetailResponse> registerBots(@PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int count) {
        assertEnabled();
        int placed = federatedService.registerBotsBatch(id, count, "Bot_");
        log.info("Admin batch-registered {} bot(s) for federation {}", placed, id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/promote")
    @Operation(summary = "Materialize READY shards into running child pyramids (up to the wave cap)")
    public ResponseEntity<FederationDetailResponse> promote(@PathVariable UUID id) {
        assertEnabled();
        int started = federatedService.promoteShards(id);
        log.info("Admin promoted {} shard(s) for federation {}", started, id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/shards/{shardIndex}/open-buyup")
    @Operation(summary = "Buy-up: close a shard's registration early + open its seat buy-out window (under-filled)")
    public ResponseEntity<FederationDetailResponse> openShardForBuyUp(@PathVariable UUID id,
            @PathVariable int shardIndex) {
        assertEnabled();
        federatedService.openShardForBuyUp(id, shardIndex);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/close-buyup")
    @Operation(summary = "Buy-up: close every shard's buy-out window and start the shards")
    public ResponseEntity<FederationDetailResponse> closeBuyUp(@PathVariable UUID id) {
        assertEnabled();
        int started = federatedService.closeBuyUpAndStart(id);
        log.info("Admin closed buy-up windows for federation {} ({} started)", id, started);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/schedule-final")
    @Operation(summary = "Schedule the final among shard winners + e-mail finalists (all shards must be done)")
    public ResponseEntity<FederationDetailResponse> scheduleFinal(@PathVariable UUID id,
            @Valid @RequestBody ScheduleTournamentRequest body) {
        assertEnabled();
        int notified = federatedService.scheduleFinal(id, body.startAt());
        log.info("Admin scheduled final for federation {} at {} ({} finalists e-mailed)",
                id, body.startAt(), notified);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/start-final")
    @Operation(summary = "Create + seed + start the final pyramid from the shard winners")
    public ResponseEntity<FederationDetailResponse> startFinal(@PathVariable UUID id) {
        assertEnabled();
        federatedService.startFinal(id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/distribute")
    @Operation(summary = "Distribute the guaranteed prize pool (expected buy-ins) across the final table by "
            + "finish position (champion-takes-most); for completed buy-up federations")
    public ResponseEntity<FederationDetailResponse> distribute(@PathVariable UUID id) {
        assertEnabled();
        federatedService.distributeFederationPrizes(id);
        log.info("Admin distributed prizes for federation {}", id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/prize-config")
    @Operation(summary = "Tune the federation's prize config (shard-winner ppm + final-table place/rest bps) "
            + "before payout")
    public ResponseEntity<FederationDetailResponse> prizeConfig(@PathVariable UUID id,
            @RequestBody PrizeConfigRequest request) {
        assertEnabled();
        federatedService.updateFederationPrizeConfig(id, request.shardWinnerPpm(),
                request.finalTablePlaceBps(), request.finalTableRestBps());
        log.info("Admin updated prize config for federation {}", id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/run-final")
    @Operation(summary = "Run the final to the grand champion (bots / simulation)")
    public ResponseEntity<FederationDetailResponse> runFinal(@PathVariable UUID id) {
        assertEnabled();
        federatedService.runFinalToChampion(id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/drain-shards")
    @Operation(summary = "Run all running shards to their winners (bots / simulation / ops)")
    public ResponseEntity<FederationDetailResponse> drainShards(@PathVariable UUID id) {
        assertEnabled();
        federatedService.drainShards(id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    private void assertEnabled() {
        if (!appProperties.getTournament().isFederatedPyramidEnabled()) {
            throw new ResourceNotFoundException("Federated pyramid is not enabled");
        }
    }
}
