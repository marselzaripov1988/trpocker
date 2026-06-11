package com.truholdem.service.tournament;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationRefund;
import com.truholdem.model.FederationRefundStatus;
import com.truholdem.model.FederationWalletStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.repository.FederationRefundRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.service.wallet.crypto.SolKeys;

/**
 * Admin-approved refund state machine for isolated-custody federations: create a refund of a FUNDED dedicated
 * wallet (PENDING_APPROVAL), a moderator approves it with the destination address (APPROVED), then the offline
 * signer assembles + broadcasts the on-chain transfer (via {@code SolRefundCoordinator}, which calls
 * {@link #forSigning}/{@link #recordBroadcast}/{@link #confirm}). The player is made whole — refunded the
 * <b>full</b> {@code grossAmount} on-chain; the operator absorbs the tiny SOL network fee. Mirrors the
 * withdrawal-approval flow; nothing is signed until a moderator approves.
 */
@Service
public class FederationRefundService {

    private static final Logger log = LoggerFactory.getLogger(FederationRefundService.class);

    private final FederationRefundRepository refundRepository;
    private final FederationPlayerWalletRepository walletRepository;
    private final PyramidFederationRepository federationRepository;

    public FederationRefundService(FederationRefundRepository refundRepository,
            FederationPlayerWalletRepository walletRepository,
            PyramidFederationRepository federationRepository) {
        this.refundRepository = refundRepository;
        this.walletRepository = walletRepository;
        this.federationRepository = federationRepository;
    }

    /** Create a PENDING_APPROVAL refund for a player's FUNDED dedicated wallet. Idempotent per wallet. */
    @Transactional
    public FederationRefund requestRefund(UUID federationId, UUID playerId) {
        PyramidFederation federation = requireFederation(federationId);
        FederationPlayerWallet wallet = walletRepository
                .findByFederationIdAndAssignedPlayerId(federationId, playerId)
                .orElseThrow(() -> new IllegalStateException("No dedicated wallet for player " + playerId));
        if (wallet.getStatus() != FederationWalletStatus.FUNDED) {
            throw new IllegalStateException("Wallet is not FUNDED (" + wallet.getStatus() + ") — nothing to refund");
        }
        return refundRepository.findByFederationIdAndWalletId(federationId, wallet.getId())
                .orElseGet(() -> createRefund(federation, wallet));
    }

    /** Bulk: create a PENDING_APPROVAL refund per FUNDED wallet (federation cancelled / under-filled). */
    @Transactional
    public int requestRefundsForCancelled(UUID federationId) {
        PyramidFederation federation = requireFederation(federationId);
        List<FederationRefund> created = new ArrayList<>();
        for (FederationPlayerWallet wallet :
                walletRepository.findByFederationIdAndStatus(federationId, FederationWalletStatus.FUNDED)) {
            if (!refundRepository.existsByFederationIdAndWalletId(federationId, wallet.getId())) {
                created.add(createRefund(federation, wallet));
            }
        }
        log.info("Federation {} — requested {} refund(s) for cancellation", federationId, created.size());
        return created.size();
    }

    private FederationRefund createRefund(PyramidFederation federation, FederationPlayerWallet wallet) {
        BigDecimal gross = wallet.getFundedAmount();
        if (gross == null || gross.signum() <= 0) {
            throw new IllegalStateException("Refund gross amount <= 0 (funded " + gross + ")");
        }
        // The player is made whole: refund the full funded buy-in on-chain, no fee withheld (the operator absorbs
        // the SOL network cost). net = gross, fee = 0 — so the wallet's ATA empties and can be closed directly.
        return refundRepository.save(new FederationRefund(
                federation.getId(), wallet.getId(), wallet.getAssignedPlayerId(),
                federation.getCryptoBuyInAsset(), gross, BigDecimal.ZERO, gross));
    }

    /** Moderator approves a pending refund, supplying the player's destination address → APPROVED. */
    @Transactional
    public FederationRefund approveRefund(UUID refundId, UUID moderatorId, String toAddress) {
        if (toAddress == null || !SolKeys.isValidAddress(toAddress)) {
            throw new IllegalArgumentException("Invalid Solana refund address: " + toAddress);
        }
        FederationRefund refund = load(refundId);
        if (refund.getStatus() != FederationRefundStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Refund " + refundId + " is not pending approval");
        }
        refund.approve(moderatorId, toAddress);
        log.info("Refund {} approved by {} → {}", refundId, moderatorId, toAddress);
        return refundRepository.save(refund);
    }

    @Transactional
    public FederationRefund rejectRefund(UUID refundId, UUID moderatorId, String reason) {
        FederationRefund refund = load(refundId);
        if (refund.getStatus() != FederationRefundStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Refund " + refundId + " is not pending approval");
        }
        refund.reject(moderatorId, reason);
        return refundRepository.save(refund);
    }

    /** Load an APPROVED refund for the offline signer. */
    @Transactional(readOnly = true)
    public FederationRefund forSigning(UUID refundId) {
        FederationRefund refund = load(refundId);
        if (refund.getStatus() != FederationRefundStatus.APPROVED) {
            throw new IllegalStateException("Refund " + refundId + " is not APPROVED (" + refund.getStatus() + ")");
        }
        return refund;
    }

    @Transactional
    public FederationRefund recordBroadcast(UUID refundId, String txId) {
        FederationRefund refund = load(refundId);
        if (refund.getStatus() != FederationRefundStatus.APPROVED) {
            throw new IllegalStateException("Refund " + refundId + " is not APPROVED");
        }
        refund.markBroadcast(txId);
        return refundRepository.save(refund);
    }

    /** Confirm a broadcast refund → CONFIRMED and mark the source wallet REFUNDED. Idempotent. */
    @Transactional
    public FederationRefund confirm(UUID refundId) {
        FederationRefund refund = load(refundId);
        if (refund.getStatus() == FederationRefundStatus.CONFIRMED) {
            return refund;
        }
        refund.markConfirmed();
        walletRepository.findById(refund.getWalletId()).ifPresent(wallet -> {
            wallet.markRefunded();
            walletRepository.save(wallet);
        });
        return refundRepository.save(refund);
    }

    public FederationRefund get(UUID refundId) {
        return load(refundId);
    }

    private FederationRefund load(UUID refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new NoSuchElementException("Refund not found: " + refundId));
    }

    private PyramidFederation requireFederation(UUID federationId) {
        return federationRepository.findById(federationId)
                .orElseThrow(() -> new NoSuchElementException("Federation not found: " + federationId));
    }
}
