package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.KycRecord;
import com.truholdem.model.KycStatus;
import com.truholdem.model.WalletAccount;
import com.truholdem.model.WalletLedgerEntry;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.KycRecordRepository;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.repository.WalletLedgerEntryRepository;
import com.truholdem.repository.WithdrawalRequestRepository;
import com.truholdem.service.wallet.WalletExceptions.InsufficientFundsException;
import com.truholdem.service.wallet.WalletExceptions.KycRequiredException;
import com.truholdem.service.wallet.WalletExceptions.PaymentsDisabledException;
import com.truholdem.service.wallet.WalletExceptions.WithdrawalCoolingPeriodException;
import com.truholdem.service.wallet.WalletExceptions.WithdrawalLimitExceededException;

/**
 * Crypto wallet: on-chain deposits (credited idempotently by tx id) + KYC-gated withdrawals. The
 * {@link WalletAccount} balance is authoritative (optimistic-locked); {@link WalletLedgerEntry} is the
 * append-only audit trail. Inert unless {@code app.payments.enabled}.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletAccountRepository accountRepository;
    private final WalletLedgerEntryRepository ledgerRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final KycRecordRepository kycRepository;
    private final CryptoPaymentProvider paymentProvider;
    private final AppProperties appProperties;

    public WalletService(
            WalletAccountRepository accountRepository,
            WalletLedgerEntryRepository ledgerRepository,
            WithdrawalRequestRepository withdrawalRepository,
            KycRecordRepository kycRepository,
            CryptoPaymentProvider paymentProvider,
            AppProperties appProperties) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.kycRepository = kycRepository;
        this.paymentProvider = paymentProvider;
        this.appProperties = appProperties;
    }

    // ---- deposits ---------------------------------------------------------------------------------

    /** Deposit address for the user to send funds to (allocated via the provider). */
    public String depositAddress(UUID userId, CryptoAsset asset) {
        requireEnabled();
        return paymentProvider.allocateDepositAddress(userId, asset);
    }

    /**
     * Credit a confirmed on-chain deposit. Idempotent by {@code txId}: a duplicate webhook (or a redelivery)
     * for the same transaction is a no-op. Returns true if this call applied the credit.
     */
    @Transactional
    public boolean creditOnChainDeposit(UUID userId, CryptoAsset asset, String txId, BigDecimal amount) {
        requireEnabled();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        if (ledgerRepository.existsByExternalTxId(txId)) {
            log.debug("Deposit {} already credited — skipping", txId);
            return false;
        }
        WalletAccount account = accountRepository.findByUserIdAndAsset(userId, asset)
                .orElseGet(() -> accountRepository.save(new WalletAccount(userId, asset)));
        account.credit(amount);
        try {
            accountRepository.save(account);
            ledgerRepository.save(WalletLedgerEntry.deposit(userId, asset, amount, account.getBalance(), txId));
        } catch (DataIntegrityViolationException e) {
            // Lost a race on the unique tx id — another thread credited the same deposit. Treat as applied.
            log.debug("Concurrent duplicate deposit {} — treated as already credited", txId);
            return false;
        }
        log.info("Credited deposit {} {} for user {} (tx {})", amount, asset, userId, txId);
        return true;
    }

    // ---- balances ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BigDecimal balance(UUID userId, CryptoAsset asset) {
        return accountRepository.findByUserIdAndAsset(userId, asset)
                .map(WalletAccount::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<WalletAccount> balances(UUID userId) {
        return accountRepository.findByUserId(userId);
    }

    // ---- KYC ------------------------------------------------------------------------------------

    @Transactional
    public KycStatus submitKyc(UUID userId) {
        requireEnabled();
        KycRecord record = kycRepository.findByUserId(userId)
                .orElseGet(() -> new KycRecord(userId, KycStatus.NONE));
        if (record.getStatus() != KycStatus.VERIFIED) {
            record.update(KycStatus.PENDING, record.getProvider(), record.getProviderRef());
            kycRepository.save(record);
        }
        return record.getStatus();
    }

    /** Apply a KYC provider decision (called by the provider webhook). */
    @Transactional
    public void recordKycDecision(UUID userId, KycStatus status, String provider, String providerRef) {
        KycRecord record = kycRepository.findByUserId(userId)
                .orElseGet(() -> new KycRecord(userId, KycStatus.NONE));
        record.update(status, provider, providerRef);
        kycRepository.save(record);
        log.info("KYC for user {} → {}", userId, status);
    }

    @Transactional(readOnly = true)
    public KycStatus kycStatus(UUID userId) {
        return kycRepository.findByUserId(userId).map(KycRecord::getStatus).orElse(KycStatus.NONE);
    }

    // ---- withdrawals ----------------------------------------------------------------------------

    /**
     * Request a withdrawal. Gated by KYC (unless disabled by config); debits the balance and creates the
     * request in one transaction, then broadcasts via the provider. Throws {@link KycRequiredException} if
     * KYC is not verified (no debit) and {@link InsufficientFundsException} if the balance is too low.
     */
    @Transactional
    public WithdrawalRequest requestWithdrawal(UUID userId, CryptoAsset asset, String toAddress,
            BigDecimal amount) {
        requireEnabled();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        if (appProperties.getPayments().isKycRequiredForWithdrawal() && kycStatus(userId) != KycStatus.VERIFIED) {
            throw new KycRequiredException();
        }
        enforceWithdrawalLimits(userId, asset, amount);

        WalletAccount account = accountRepository.findByUserIdAndAsset(userId, asset)
                .orElseThrow(InsufficientFundsException::new);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        account.debit(amount);
        accountRepository.save(account);

        boolean approvalRequired = appProperties.getPayments().isWithdrawalApprovalRequired();
        WithdrawalStatus initialStatus = approvalRequired
                ? WithdrawalStatus.PENDING_APPROVAL : WithdrawalStatus.APPROVED;
        WithdrawalRequest request = withdrawalRepository.save(
                new WithdrawalRequest(userId, asset, toAddress, amount, initialStatus));
        ledgerRepository.save(WalletLedgerEntry.withdrawal(userId, asset, amount, account.getBalance(),
                request.getId()));

        if (approvalRequired) {
            log.info("Withdrawal {} {} for user {} → {} awaiting moderator approval",
                    amount, asset, userId, toAddress);
            return request;
        }

        String txId = paymentProvider.broadcastWithdrawal(userId, asset, toAddress, amount);
        request.markBroadcast(txId);
        withdrawalRepository.save(request);

        log.info("Withdrawal {} {} for user {} → {} broadcast (tx {})", amount, asset, userId, toAddress, txId);
        return request;
    }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> pendingApprovals() {
        return withdrawalRepository.findByStatusOrderByCreatedAtAsc(WithdrawalStatus.PENDING_APPROVAL);
    }

    /** Withdrawals for the moderation page: a single {@code status} if given, else the open review set
     *  (PENDING_APPROVAL awaiting a decision + APPROVED awaiting the offline-signer broadcast). */
    @Transactional(readOnly = true)
    public List<WithdrawalRequest> withdrawalsForReview(WithdrawalStatus status) {
        if (status != null) {
            return withdrawalRepository.findByStatusOrderByCreatedAtAsc(status);
        }
        return withdrawalRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(WithdrawalStatus.PENDING_APPROVAL, WithdrawalStatus.APPROVED));
    }

    /** Load a single withdrawal by id (read-only) — used by the ETH coordinator to reconcile a broadcast. */
    @Transactional(readOnly = true)
    public WithdrawalRequest getWithdrawal(UUID withdrawalId) {
        return withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
    }

    /**
     * An APPROVED withdrawal awaiting an offline signature (the offline-signer / PSBT handoff). Returned so
     * the offline signer can build + sign + broadcast the chain transaction; the resulting tx id is recorded
     * back via {@link #recordBroadcast}. Only valid for an APPROVED request.
     */
    @Transactional(readOnly = true)
    public WithdrawalRequest withdrawalForSigning(UUID withdrawalId) {
        WithdrawalRequest request = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
        if (request.getStatus() != WithdrawalStatus.APPROVED) {
            throw new IllegalStateException(
                    "Withdrawal " + withdrawalId + " is not awaiting signing (status " + request.getStatus() + ")");
        }
        return request;
    }

    /**
     * Record the on-chain tx id after the offline signer broadcast an APPROVED withdrawal → BROADCAST. Only
     * valid for an APPROVED request; afterwards the {@code withdrawal-status} webhook confirms/fails it.
     */
    @Transactional
    public WithdrawalRequest recordBroadcast(UUID withdrawalId, String txId) {
        requireEnabled();
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId is required");
        }
        WithdrawalRequest request = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
        if (request.getStatus() != WithdrawalStatus.APPROVED) {
            throw new IllegalStateException(
                    "Withdrawal " + withdrawalId + " is not APPROVED (status " + request.getStatus() + ")");
        }
        request.markBroadcast(txId);
        log.info("Withdrawal {} broadcast off-chain by the signer (tx {})", withdrawalId, txId);
        return withdrawalRepository.save(request);
    }

    /**
     * Moderator approves a pending withdrawal → broadcast via the provider (BROADCAST). For a provider that
     * cannot broadcast in-process (e.g. offline-pool), it stays APPROVED, awaiting the offline signer. Only a
     * PENDING_APPROVAL request can be approved (else {@link IllegalStateException}).
     */
    @Transactional
    public WithdrawalRequest approveWithdrawal(UUID withdrawalId, UUID moderatorId) {
        requireEnabled();
        WithdrawalRequest request = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
        if (request.getStatus() != WithdrawalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Withdrawal " + withdrawalId + " is not pending approval");
        }
        int coolingMinutes = appProperties.getPayments().getWithdrawalCoolingPeriodMinutes();
        if (coolingMinutes > 0) {
            Instant earliest = request.getCreatedAt().plus(coolingMinutes, ChronoUnit.MINUTES);
            if (Instant.now().isBefore(earliest)) {
                throw new WithdrawalCoolingPeriodException(
                        "Withdrawal " + withdrawalId + " is in its cooling period until " + earliest);
            }
        }
        request.approve(moderatorId);
        try {
            String txId = paymentProvider.broadcastWithdrawal(
                    request.getUserId(), request.getAsset(), request.getToAddress(), request.getAmount());
            request.markBroadcast(txId);
            log.info("Withdrawal {} approved by {} and broadcast (tx {})", withdrawalId, moderatorId, txId);
        } catch (UnsupportedOperationException e) {
            // Provider broadcasts out-of-band (offline signer / PSBT handoff) — keep APPROVED.
            log.info("Withdrawal {} approved by {} — awaiting offline broadcast", withdrawalId, moderatorId);
        }
        return withdrawalRepository.save(request);
    }

    /**
     * Moderator rejects a pending withdrawal → REJECTED and credits the debited amount back (a
     * {@code WITHDRAWAL_REVERSAL} ledger entry). Only a PENDING_APPROVAL request can be rejected.
     */
    @Transactional
    public WithdrawalRequest rejectWithdrawal(UUID withdrawalId, UUID moderatorId, String reason) {
        requireEnabled();
        WithdrawalRequest request = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
        if (request.getStatus() != WithdrawalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Withdrawal " + withdrawalId + " is not pending approval");
        }
        WalletAccount account = accountRepository.findByUserIdAndAsset(request.getUserId(), request.getAsset())
                .orElseGet(() -> accountRepository.save(new WalletAccount(request.getUserId(), request.getAsset())));
        account.credit(request.getAmount());
        accountRepository.save(account);
        ledgerRepository.save(WalletLedgerEntry.withdrawalReversal(request.getUserId(), request.getAsset(),
                request.getAmount(), account.getBalance(), request.getId()));
        request.reject(moderatorId, reason);
        log.info("Withdrawal {} rejected by {} — reversed {} {} to user {}",
                withdrawalId, moderatorId, request.getAmount(), request.getAsset(), request.getUserId());
        return withdrawalRepository.save(request);
    }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> withdrawals(UUID userId) {
        return withdrawalRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Enforce the configured per-transaction and rolling-24h withdrawal limits for the asset (no-op when
     *  unconfigured). Counts everything not reversed (REJECTED/FAILED) toward the daily total. */
    private void enforceWithdrawalLimits(UUID userId, CryptoAsset asset, BigDecimal amount) {
        BigDecimal perTx = appProperties.getPayments().getMaxWithdrawalPerTx().get(asset.name());
        if (perTx != null && amount.compareTo(perTx) > 0) {
            throw new WithdrawalLimitExceededException(
                    "Amount " + amount + " exceeds the per-transaction limit of " + perTx + " " + asset);
        }
        BigDecimal perDay = appProperties.getPayments().getMaxWithdrawalPerDay().get(asset.name());
        if (perDay != null) {
            Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
            BigDecimal used = withdrawalRepository
                    .findByUserIdAndAssetAndCreatedAtAfter(userId, asset, cutoff).stream()
                    .filter(w -> w.getStatus() != WithdrawalStatus.REJECTED
                            && w.getStatus() != WithdrawalStatus.FAILED)
                    .map(WithdrawalRequest::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (used.add(amount).compareTo(perDay) > 0) {
                throw new WithdrawalLimitExceededException("Amount " + amount + " exceeds the 24h limit of "
                        + perDay + " " + asset + " (already used " + used + ")");
            }
        }
    }

    /**
     * Provider callback: the broadcast withdrawal reached on-chain confirmation. Idempotent — a redelivered
     * callback is a no-op once CONFIRMED.
     */
    @Transactional
    public void confirmWithdrawal(UUID withdrawalId) {
        requireEnabled();
        WithdrawalRequest request = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
        if (request.getStatus() == WithdrawalStatus.CONFIRMED) {
            return;
        }
        request.markConfirmed();
        withdrawalRepository.save(request);
        log.info("Withdrawal {} confirmed", withdrawalId);
    }

    /**
     * Provider callback: the withdrawal failed to settle on-chain. Marks it FAILED and credits the debited
     * amount back to the balance (a {@code WITHDRAWAL_REVERSAL} ledger entry). Idempotent — once FAILED a
     * redelivered callback is a no-op; a CONFIRMED withdrawal cannot be reversed.
     */
    @Transactional
    public void failWithdrawal(UUID withdrawalId) {
        requireEnabled();
        WithdrawalRequest request = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new NoSuchElementException("Withdrawal not found: " + withdrawalId));
        if (request.getStatus() == WithdrawalStatus.FAILED) {
            return;
        }
        if (request.getStatus() == WithdrawalStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot fail a confirmed withdrawal: " + withdrawalId);
        }
        WalletAccount account = accountRepository.findByUserIdAndAsset(request.getUserId(), request.getAsset())
                .orElseGet(() -> accountRepository.save(new WalletAccount(request.getUserId(), request.getAsset())));
        account.credit(request.getAmount());
        accountRepository.save(account);
        ledgerRepository.save(WalletLedgerEntry.withdrawalReversal(request.getUserId(), request.getAsset(),
                request.getAmount(), account.getBalance(), request.getId()));
        request.markFailed();
        withdrawalRepository.save(request);
        log.info("Withdrawal {} failed — reversed {} {} to user {}",
                withdrawalId, request.getAmount(), request.getAsset(), request.getUserId());
    }

    // ---- internal (off-chain) movements: real-money tournament buy-in / payout ---------------------

    /**
     * Debit the wallet for a real-money tournament buy-in. Idempotent by {@code idempotencyKey} (stored in
     * the unique external-tx column) — a repeated charge for the same key is a no-op returning false.
     * Throws {@link InsufficientFundsException} when the balance is too low.
     */
    @Transactional
    public boolean chargeBuyIn(UUID userId, CryptoAsset asset, BigDecimal amount, String idempotencyKey) {
        requireEnabled();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Buy-in amount must be positive");
        }
        if (ledgerRepository.existsByExternalTxId(idempotencyKey)) {
            return false;
        }
        WalletAccount account = accountRepository.findByUserIdAndAsset(userId, asset)
                .orElseThrow(InsufficientFundsException::new);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        account.debit(amount);
        accountRepository.save(account);
        ledgerRepository.save(WalletLedgerEntry.tournamentBuyIn(userId, asset, amount, account.getBalance(),
                idempotencyKey));
        log.info("Tournament buy-in: debited {} {} from user {} ({})", amount, asset, userId, idempotencyKey);
        return true;
    }

    /**
     * Credit the wallet with a real-money tournament payout/prize. Idempotent by {@code idempotencyKey}.
     */
    @Transactional
    public boolean awardPayout(UUID userId, CryptoAsset asset, BigDecimal amount, String idempotencyKey) {
        requireEnabled();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payout amount must be positive");
        }
        if (ledgerRepository.existsByExternalTxId(idempotencyKey)) {
            return false;
        }
        WalletAccount account = accountRepository.findByUserIdAndAsset(userId, asset)
                .orElseGet(() -> accountRepository.save(new WalletAccount(userId, asset)));
        account.credit(amount);
        accountRepository.save(account);
        ledgerRepository.save(WalletLedgerEntry.tournamentPayout(userId, asset, amount, account.getBalance(),
                idempotencyKey));
        log.info("Tournament payout: credited {} {} to user {} ({})", amount, asset, userId, idempotencyKey);
        return true;
    }

    /**
     * Credit a real-money tournament buy-in back to the wallet when the tournament is cancelled. Idempotent by
     * {@code idempotencyKey} (so a re-run of the cancel sweep does not double-refund).
     */
    @Transactional
    public boolean refundBuyIn(UUID userId, CryptoAsset asset, BigDecimal amount, String idempotencyKey) {
        requireEnabled();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (ledgerRepository.existsByExternalTxId(idempotencyKey)) {
            return false;
        }
        WalletAccount account = accountRepository.findByUserIdAndAsset(userId, asset)
                .orElseGet(() -> accountRepository.save(new WalletAccount(userId, asset)));
        account.credit(amount);
        accountRepository.save(account);
        ledgerRepository.save(WalletLedgerEntry.tournamentRefund(userId, asset, amount, account.getBalance(),
                idempotencyKey));
        log.info("Tournament refund: credited {} {} to user {} ({})", amount, asset, userId, idempotencyKey);
        return true;
    }

    private void requireEnabled() {
        if (!appProperties.getPayments().isEnabled()) {
            throw new PaymentsDisabledException();
        }
    }
}
