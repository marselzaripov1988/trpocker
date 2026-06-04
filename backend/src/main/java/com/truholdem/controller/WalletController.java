package com.truholdem.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.ErrorResponse;
import com.truholdem.dto.wallet.CreateWithdrawalRequest;
import com.truholdem.dto.wallet.DepositAddressResponse;
import com.truholdem.dto.wallet.KycStatusResponse;
import com.truholdem.dto.wallet.WalletBalanceResponse;
import com.truholdem.dto.wallet.WithdrawalResponse;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.KycStatus;
import com.truholdem.model.User;
import com.truholdem.service.wallet.KycVerificationService;
import com.truholdem.service.wallet.WalletExceptions.InsufficientFundsException;
import com.truholdem.service.wallet.WalletExceptions.KycRequiredException;
import com.truholdem.service.wallet.WalletExceptions.PaymentsDisabledException;
import com.truholdem.service.wallet.WalletExceptions.WithdrawalLimitExceededException;
import com.truholdem.service.wallet.WalletService;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * User-facing crypto wallet API: balances, deposit address, KYC submit/status, withdrawals.
 * Withdrawals are gated by KYC (see {@link WalletService}). All endpoints require authentication; the
 * subsystem is inert unless {@code app.payments.enabled}.
 */
@RestController
@ApiV1Config
@RequestMapping("/wallet")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {

    private final WalletService walletService;
    private final KycVerificationService kycVerificationService;

    public WalletController(WalletService walletService, KycVerificationService kycVerificationService) {
        this.walletService = walletService;
        this.kycVerificationService = kycVerificationService;
    }

    private static UUID userId(UserDetails principal) {
        return ((User) principal).getId();
    }

    @GetMapping("/balances")
    public ResponseEntity<List<WalletBalanceResponse>> balances(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(
                walletService.balances(userId(principal)).stream().map(WalletBalanceResponse::from).toList());
    }

    @PostMapping("/deposit-address")
    public ResponseEntity<?> depositAddress(@AuthenticationPrincipal UserDetails principal,
            @RequestParam CryptoAsset asset) {
        try {
            String address = walletService.depositAddress(userId(principal), asset);
            return ResponseEntity.ok(DepositAddressResponse.of(asset, address));
        } catch (PaymentsDisabledException e) {
            return paymentsDisabled();
        }
    }

    @GetMapping("/kyc")
    public ResponseEntity<KycStatusResponse> kyc(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(new KycStatusResponse(walletService.kycStatus(userId(principal))));
    }

    @PostMapping("/kyc/submit")
    public ResponseEntity<?> submitKyc(@AuthenticationPrincipal UserDetails principal) {
        try {
            return ResponseEntity.ok(new KycStatusResponse(walletService.submitKyc(userId(principal))));
        } catch (PaymentsDisabledException e) {
            return paymentsDisabled();
        }
    }

    /** Upload a KYC verification video (e.g. the user holding their passport); moves KYC to PENDING. */
    @PostMapping(value = "/kyc/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadKycDocument(@AuthenticationPrincipal UserDetails principal,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("EMPTY_FILE", "No file uploaded"));
        }
        try {
            KycStatus status = kycVerificationService.submitVerificationVideo(
                    userId(principal), file.getBytes(), file.getOriginalFilename(), file.getContentType());
            return ResponseEntity.ok(new KycStatusResponse(status));
        } catch (PaymentsDisabledException e) {
            return paymentsDisabled();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("UPLOAD_FAILED", "Could not read the uploaded file"));
        }
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<?> requestWithdrawal(@AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateWithdrawalRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(WithdrawalResponse.from(
                    walletService.requestWithdrawal(userId(principal), request.asset(),
                            request.toAddress(), request.amount())));
        } catch (PaymentsDisabledException e) {
            return paymentsDisabled();
        } catch (KycRequiredException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("KYC_REQUIRED", e.getMessage()));
        } catch (InsufficientFundsException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(new ErrorResponse("INSUFFICIENT_FUNDS", e.getMessage()));
        } catch (WithdrawalLimitExceededException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(new ErrorResponse("WITHDRAWAL_LIMIT", e.getMessage()));
        }
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<List<WithdrawalResponse>> withdrawals(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(
                walletService.withdrawals(userId(principal)).stream().map(WithdrawalResponse::from).toList());
    }

    private static ResponseEntity<ErrorResponse> paymentsDisabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("PAYMENTS_DISABLED", "Payments are disabled"));
    }
}
