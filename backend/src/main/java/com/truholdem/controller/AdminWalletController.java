package com.truholdem.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.wallet.PoolImportRequest;
import com.truholdem.dto.wallet.PoolImportResponse;
import com.truholdem.dto.wallet.PoolStatusResponse;
import com.truholdem.service.wallet.DepositAddressPoolService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin API for the offline-generated deposit-address pool: import a batch of public addresses (generated
 * offline, private keys never sent), and inspect remaining depth. ADMIN role required.
 */
@RestController
@ApiV1Config
@RequestMapping("/admin/wallet")
@Tag(name = "Admin Wallet", description = "Deposit-address pool management (ADMIN role required)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWalletController {

    private final DepositAddressPoolService pool;

    public AdminWalletController(DepositAddressPoolService pool) {
        this.pool = pool;
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
}
