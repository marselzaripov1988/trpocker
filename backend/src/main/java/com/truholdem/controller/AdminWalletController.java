package com.truholdem.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.wallet.AdminWithdrawalDto;
import com.truholdem.dto.wallet.BroadcastWithdrawalRequest;
import com.truholdem.dto.wallet.BtcBroadcastRequest;
import com.truholdem.dto.wallet.BtcConfirmationDto;
import com.truholdem.dto.wallet.BtcUnsignedTxDto;
import com.truholdem.dto.wallet.EthBroadcastRequest;
import com.truholdem.dto.wallet.EthConfirmationDto;
import com.truholdem.dto.wallet.EthUnsignedTxDto;
import com.truholdem.dto.wallet.KycDecisionRequest;
import com.truholdem.dto.wallet.KycPendingDto;
import com.truholdem.dto.wallet.KycReEncryptResult;
import com.truholdem.dto.wallet.KycStatusResponse;
import com.truholdem.dto.wallet.PoolImportRequest;
import com.truholdem.dto.wallet.PoolImportResponse;
import com.truholdem.dto.wallet.PoolStatusResponse;
import com.truholdem.dto.wallet.RejectWithdrawalRequest;
import com.truholdem.dto.wallet.WithdrawalSigningRequestDto;
import com.truholdem.model.User;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.DepositAddressPoolService;
import com.truholdem.service.wallet.KycVerificationService;
import com.truholdem.service.wallet.WalletService;
import com.truholdem.service.wallet.btc.BtcWithdrawalCoordinator;
import com.truholdem.service.wallet.eth.EthWithdrawalCoordinator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin API for the crypto wallet: deposit-address pool management (import / depth) and KYC moderation
 * (review the uploaded verification video, then decide). ADMIN role required.
 */
@RestController
@ApiV1Config
@RequestMapping("/admin/wallet")
@Tag(name = "Admin Wallet", description = "Deposit-address pool + KYC moderation (ADMIN role required)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWalletController {

    private final DepositAddressPoolService pool;
    private final KycVerificationService kycVerificationService;
    private final WalletService walletService;
    private final ObjectProvider<EthWithdrawalCoordinator> ethCoordinator;
    private final ObjectProvider<BtcWithdrawalCoordinator> btcCoordinator;

    public AdminWalletController(DepositAddressPoolService pool,
            KycVerificationService kycVerificationService, WalletService walletService,
            ObjectProvider<EthWithdrawalCoordinator> ethCoordinator,
            ObjectProvider<BtcWithdrawalCoordinator> btcCoordinator) {
        this.pool = pool;
        this.kycVerificationService = kycVerificationService;
        this.walletService = walletService;
        this.ethCoordinator = ethCoordinator;
        this.btcCoordinator = btcCoordinator;
    }

    @PostMapping("/deposit-pool/import")
    @Operation(summary = "Import a batch of offline-generated public deposit addresses")
    public ResponseEntity<PoolImportResponse> importPool(@Valid @RequestBody PoolImportRequest request) {
        return ResponseEntity.ok(pool.importBatch(request.addresses()));
    }

    @GetMapping("/deposit-pool/status")
    @Operation(summary = "Free/assigned deposit-address counts per asset")
    public ResponseEntity<PoolStatusResponse> status() {
        return ResponseEntity.ok(pool.status());
    }

    @GetMapping("/kyc/pending")
    @Operation(summary = "List pending KYC submissions awaiting review")
    public ResponseEntity<List<KycPendingDto>> pendingKyc() {
        return ResponseEntity.ok(kycVerificationService.listPending());
    }

    @GetMapping("/kyc/{userId}/document")
    @Operation(summary = "Download a user's latest KYC verification video for review")
    public ResponseEntity<byte[]> kycDocument(@PathVariable UUID userId) {
        return kycVerificationService.loadLatest(userId)
                .map(doc -> ResponseEntity.ok()
                        .contentType(parseType(doc.contentType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                                + (doc.filename() != null ? doc.filename() : "kyc-video") + "\"")
                        .body(doc.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/kyc/{userId}/decision")
    @Operation(summary = "Approve or reject a user's KYC after reviewing the document")
    public ResponseEntity<KycStatusResponse> decideKyc(@PathVariable UUID userId,
            @Valid @RequestBody KycDecisionRequest request) {
        walletService.recordKycDecision(userId, request.status(), "manual", request.note());
        return ResponseEntity.ok(new KycStatusResponse(request.status()));
    }

    @DeleteMapping("/kyc/{userId}/documents")
    @Operation(summary = "GDPR erasure: delete all of a user's KYC verification media")
    public ResponseEntity<Map<String, Integer>> eraseKyc(@PathVariable UUID userId) {
        return ResponseEntity.ok(Map.of("erased", kycVerificationService.eraseForUser(userId)));
    }

    @PostMapping("/kyc/re-encrypt")
    @Operation(summary = "Re-encrypt all KYC media under the active key/provider (rotation / KMS migration)")
    public ResponseEntity<KycReEncryptResult> reEncryptKyc() {
        return ResponseEntity.ok(kycVerificationService.reEncryptAll());
    }

    @GetMapping("/withdrawals")
    @Operation(summary = "List withdrawals for moderation (optional ?status=; default = pending + approved)")
    public ResponseEntity<List<AdminWithdrawalDto>> withdrawals(
            @RequestParam(required = false) WithdrawalStatus status) {
        return ResponseEntity.ok(
                walletService.withdrawalsForReview(status).stream().map(AdminWithdrawalDto::from).toList());
    }

    @PostMapping("/withdrawals/{id}/approve")
    @Operation(summary = "Approve a pending withdrawal (→ broadcast / offline-signer handoff)")
    public ResponseEntity<AdminWithdrawalDto> approveWithdrawal(@PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(AdminWithdrawalDto.from(
                walletService.approveWithdrawal(id, ((User) principal).getId())));
    }

    @PostMapping("/withdrawals/{id}/reject")
    @Operation(summary = "Reject a pending withdrawal (→ reverse the debit)")
    public ResponseEntity<AdminWithdrawalDto> rejectWithdrawal(@PathVariable UUID id,
            @RequestBody(required = false) RejectWithdrawalRequest body,
            @AuthenticationPrincipal UserDetails principal) {
        String reason = body != null ? body.reason() : null;
        return ResponseEntity.ok(AdminWithdrawalDto.from(
                walletService.rejectWithdrawal(id, ((User) principal).getId(), reason)));
    }

    @GetMapping("/withdrawals/{id}/unsigned")
    @Operation(summary = "Export an approved withdrawal for the offline signer (PSBT/raw-tx handoff)")
    public ResponseEntity<WithdrawalSigningRequestDto> exportForSigning(@PathVariable UUID id) {
        return ResponseEntity.ok(WithdrawalSigningRequestDto.from(walletService.withdrawalForSigning(id)));
    }

    @PostMapping("/withdrawals/{id}/broadcast")
    @Operation(summary = "Record the tx id after the offline signer broadcast an approved withdrawal")
    public ResponseEntity<AdminWithdrawalDto> recordBroadcast(@PathVariable UUID id,
            @Valid @RequestBody BroadcastWithdrawalRequest body) {
        return ResponseEntity.ok(AdminWithdrawalDto.from(walletService.recordBroadcast(id, body.txId())));
    }

    @GetMapping("/withdrawals/{id}/eth-unsigned")
    @Operation(summary = "Assemble an unsigned ETH/ERC-20 tx from live node state for the offline signer")
    public ResponseEntity<EthUnsignedTxDto> ethUnsigned(@PathVariable UUID id) {
        return ResponseEntity.ok(ethCoordinatorOrThrow().buildUnsigned(id));
    }

    @PostMapping("/withdrawals/{id}/eth-broadcast")
    @Operation(summary = "Broadcast the offline-signed raw ETH tx and record its hash (→ BROADCAST)")
    public ResponseEntity<AdminWithdrawalDto> ethBroadcast(@PathVariable UUID id,
            @Valid @RequestBody EthBroadcastRequest body) {
        return ResponseEntity.ok(
                AdminWithdrawalDto.from(ethCoordinatorOrThrow().broadcast(id, body.signedRawTx())));
    }

    @PostMapping("/withdrawals/{id}/eth-reconcile")
    @Operation(summary = "Reconcile a broadcast ETH withdrawal against its receipt (→ CONFIRMED / FAILED)")
    public ResponseEntity<AdminWithdrawalDto> ethReconcile(@PathVariable UUID id) {
        return ResponseEntity.ok(AdminWithdrawalDto.from(ethCoordinatorOrThrow().reconcile(id)));
    }

    @GetMapping("/withdrawals/{id}/eth-confirmation")
    @Operation(summary = "On-chain confirmation status of a broadcast ETH withdrawal's transaction")
    public ResponseEntity<EthConfirmationDto> ethConfirmation(@PathVariable UUID id) {
        String txId = walletService.getWithdrawal(id).getTxId();
        if (txId == null || txId.isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(ethCoordinatorOrThrow().confirmation(txId));
    }

    private EthWithdrawalCoordinator ethCoordinatorOrThrow() {
        EthWithdrawalCoordinator coordinator = ethCoordinator.getIfAvailable();
        if (coordinator == null) {
            throw new IllegalStateException("ETH RPC coordinator is disabled (app.payments.eth-rpc-enabled)");
        }
        return coordinator;
    }

    @GetMapping("/withdrawals/{id}/btc-unsigned")
    @Operation(summary = "Assemble an unsigned BTC (P2WPKH) tx (UTXO selection + fee) for the offline signer")
    public ResponseEntity<BtcUnsignedTxDto> btcUnsigned(@PathVariable UUID id) {
        return ResponseEntity.ok(btcCoordinatorOrThrow().buildUnsigned(id));
    }

    @PostMapping("/withdrawals/{id}/btc-broadcast")
    @Operation(summary = "Broadcast the offline-signed raw BTC tx and record its txid (→ BROADCAST)")
    public ResponseEntity<AdminWithdrawalDto> btcBroadcast(@PathVariable UUID id,
            @Valid @RequestBody BtcBroadcastRequest body) {
        return ResponseEntity.ok(
                AdminWithdrawalDto.from(btcCoordinatorOrThrow().broadcast(id, body.signedRawTx())));
    }

    @PostMapping("/withdrawals/{id}/btc-reconcile")
    @Operation(summary = "Reconcile a broadcast BTC withdrawal against its confirmations (→ CONFIRMED)")
    public ResponseEntity<AdminWithdrawalDto> btcReconcile(@PathVariable UUID id) {
        return ResponseEntity.ok(AdminWithdrawalDto.from(btcCoordinatorOrThrow().reconcile(id)));
    }

    @GetMapping("/withdrawals/{id}/btc-confirmation")
    @Operation(summary = "On-chain confirmation status of a broadcast BTC withdrawal's transaction")
    public ResponseEntity<BtcConfirmationDto> btcConfirmation(@PathVariable UUID id) {
        String txId = walletService.getWithdrawal(id).getTxId();
        if (txId == null || txId.isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok(btcCoordinatorOrThrow().confirmation(txId));
    }

    private BtcWithdrawalCoordinator btcCoordinatorOrThrow() {
        BtcWithdrawalCoordinator coordinator = btcCoordinator.getIfAvailable();
        if (coordinator == null) {
            throw new IllegalStateException("BTC RPC coordinator is disabled (app.payments.btc-rpc-enabled)");
        }
        return coordinator;
    }

    private static MediaType parseType(String contentType) {
        try {
            return contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
