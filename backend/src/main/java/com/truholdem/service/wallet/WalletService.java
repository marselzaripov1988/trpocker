package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.List;
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
import com.truholdem.repository.KycRecordRepository;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.repository.WalletLedgerEntryRepository;
import com.truholdem.repository.WithdrawalRequestRepository;
import com.truholdem.service.wallet.WalletExceptions.InsufficientFundsException;
import com.truholdem.service.wallet.WalletExceptions.KycRequiredException;
import com.truholdem.service.wallet.WalletExceptions.PaymentsDisabledException;

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

        WalletAccount account = accountRepository.findByUserIdAndAsset(userId, asset)
                .orElseThrow(InsufficientFundsException::new);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        account.debit(amount);
        accountRepository.save(account);

        WithdrawalRequest request = withdrawalRepository.save(
                new WithdrawalRequest(userId, asset, toAddress, amount));
        ledgerRepository.save(WalletLedgerEntry.withdrawal(userId, asset, amount, account.getBalance(),
                request.getId()));

        String txId = paymentProvider.broadcastWithdrawal(userId, asset, toAddress, amount);
        request.markBroadcast(txId);
        withdrawalRepository.save(request);

        log.info("Withdrawal {} {} for user {} → {} broadcast (tx {})", amount, asset, userId, toAddress, txId);
        return request;
    }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> withdrawals(UUID userId) {
        return withdrawalRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private void requireEnabled() {
        if (!appProperties.getPayments().isEnabled()) {
            throw new PaymentsDisabledException();
        }
    }
}
