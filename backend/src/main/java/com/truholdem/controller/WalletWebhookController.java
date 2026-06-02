package com.truholdem.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.DepositConfirmationRequest;
import com.truholdem.dto.wallet.KycCallbackRequest;
import com.truholdem.service.wallet.WalletService;

import jakarta.validation.Valid;

/**
 * Inbound provider webhooks (payment gateway / KYC provider): confirmed on-chain deposit and KYC decision.
 * Mounted under {@code /internal/**} (permitted in SecurityConfig — no user JWT) and authenticated by a
 * constant-time shared-secret header, mirroring {@link ClusterInternalController}. Rejects everything when
 * payments are disabled or no secret is configured, so it is never an open mutation hole.
 */
@RestController
@RequestMapping("/internal/wallet")
public class WalletWebhookController {

    public static final String SECRET_HEADER = "X-Payments-Secret";

    private final WalletService walletService;
    private final AppProperties appProperties;

    public WalletWebhookController(WalletService walletService, AppProperties appProperties) {
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    @PostMapping("/deposit")
    public ResponseEntity<Void> depositConfirmed(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @Valid @RequestBody DepositConfirmationRequest request) {
        if (!authorized(secret)) {
            return ResponseEntity.status(403).build();
        }
        walletService.creditOnChainDeposit(request.userId(), request.asset(), request.txId(), request.amount());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kyc-callback")
    public ResponseEntity<Void> kycCallback(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @Valid @RequestBody KycCallbackRequest request) {
        if (!authorized(secret)) {
            return ResponseEntity.status(403).build();
        }
        walletService.recordKycDecision(request.userId(), request.status(),
                request.provider(), request.providerRef());
        return ResponseEntity.ok().build();
    }

    /** Constant-time secret comparison; rejects when payments are off or the secret is unconfigured. */
    private boolean authorized(String provided) {
        if (!appProperties.getPayments().isEnabled()) {
            return false;
        }
        String expected = appProperties.getPayments().getWebhookSecret();
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
