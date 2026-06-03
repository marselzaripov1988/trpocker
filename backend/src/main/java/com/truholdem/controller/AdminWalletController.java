package com.truholdem.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.wallet.KycDecisionRequest;
import com.truholdem.dto.wallet.KycStatusResponse;
import com.truholdem.dto.wallet.PoolImportRequest;
import com.truholdem.dto.wallet.PoolImportResponse;
import com.truholdem.dto.wallet.PoolStatusResponse;
import com.truholdem.service.wallet.DepositAddressPoolService;
import com.truholdem.service.wallet.KycVerificationService;
import com.truholdem.service.wallet.WalletService;

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

    public AdminWalletController(DepositAddressPoolService pool,
            KycVerificationService kycVerificationService, WalletService walletService) {
        this.pool = pool;
        this.kycVerificationService = kycVerificationService;
        this.walletService = walletService;
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

    private static MediaType parseType(String contentType) {
        try {
            return contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
