package com.truholdem.service.tournament;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.dto.FederationWalletImportRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationWalletStatus;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.service.wallet.crypto.SolKeys;

/**
 * Manages the pool of dedicated per-player wallets for an isolated-custody federated pyramid: imports the
 * offline-generated wallets (FREE), hands one out per player (ASSIGNED), and records the on-chain buy-in deposit
 * (FUNDED). Keys are never here — only public addresses + derivation indices. Seating a funded player into a
 * shard is done by {@link FederatedPyramidService} (which owns the shard fill-order logic).
 */
@Service
public class FederationPlayerWalletService {

    private static final Logger log = LoggerFactory.getLogger(FederationPlayerWalletService.class);
    private static final int MAX_ADDRESS_LENGTH = 64;

    private final FederationPlayerWalletRepository repository;

    public FederationPlayerWalletService(FederationPlayerWalletRepository repository) {
        this.repository = repository;
    }

    /** Import a chunk of offline-generated wallets for a federation as FREE. Validates base58 addresses and skips
     *  already-imported ones (idempotent re-import) via a single bulk existence query + batch insert — so a 1M
     *  field can be imported chunk-by-chunk efficiently. Returns the number inserted. */
    @Transactional
    public int importBatch(UUID federationId, CryptoAsset asset, List<FederationWalletImportRequest.Entry> entries) {
        for (FederationWalletImportRequest.Entry e : entries) {
            requireValid(e.address(), "address");
            requireValid(e.ownerPubkey(), "ownerPubkey");
        }
        java.util.Set<String> seen = new java.util.HashSet<>(repository.findExistingAddresses(
                federationId, entries.stream().map(FederationWalletImportRequest.Entry::address).toList()));
        List<FederationPlayerWallet> toSave = new java.util.ArrayList<>();
        for (FederationWalletImportRequest.Entry e : entries) {
            if (seen.add(e.address())) { // not already imported and not duplicated within this chunk
                toSave.add(new FederationPlayerWallet(
                        federationId, e.derivationIndex(), e.ownerPubkey(), e.address(), asset));
            }
        }
        repository.saveAll(toSave);
        log.info("Federation {} — imported {} player wallet(s) ({} skipped as duplicates)",
                federationId, toSave.size(), entries.size() - toSave.size());
        return toSave.size();
    }

    /** Hand the player their dedicated wallet for the federation: the existing one if already assigned, else the
     *  lowest-index FREE wallet (row-locked). Throws if the pool is exhausted. Must run in a transaction. */
    @Transactional
    public FederationPlayerWallet assign(UUID federationId, UUID playerId) {
        return repository.findByFederationIdAndAssignedPlayerId(federationId, playerId).orElseGet(() -> {
            FederationPlayerWallet free = repository
                    .findFirstByFederationIdAndStatusOrderByDerivationIndexAsc(federationId, FederationWalletStatus.FREE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Federation " + federationId + " has no free player wallets — import more"));
            free.assignTo(playerId);
            FederationPlayerWallet saved = repository.save(free);
            log.info("Federation {} — assigned wallet #{} ({}) to player {}",
                    federationId, saved.getDerivationIndex(), saved.getAddress(), playerId);
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public List<FederationPlayerWallet> assignedAwaitingDeposit(UUID federationId) {
        return repository.findByFederationIdAndStatus(federationId, FederationWalletStatus.ASSIGNED);
    }

    /** Mark a wallet's on-chain buy-in deposit confirmed and return the player to seat. Idempotent: returns
     *  empty if the wallet is already FUNDED or not assigned to a player. */
    @Transactional
    public java.util.Optional<UUID> confirmFunding(UUID federationId, String address, String depositTxId,
            BigDecimal amount) {
        FederationPlayerWallet wallet = repository.findByFederationIdAndAddress(federationId, address)
                .orElseThrow(() -> new IllegalArgumentException("Unknown wallet " + address));
        if (wallet.getStatus() != FederationWalletStatus.ASSIGNED || wallet.getAssignedPlayerId() == null) {
            return java.util.Optional.empty();
        }
        wallet.markFunded(depositTxId, amount);
        repository.save(wallet);
        return java.util.Optional.of(wallet.getAssignedPlayerId());
    }

    /** Release an assigned-but-unfunded wallet back to the FREE pool (a no-show). */
    @Transactional
    public void release(FederationPlayerWallet wallet) {
        wallet.release();
        repository.save(wallet);
    }

    /** The next batch of wallets (FREE buffer + ASSIGNED) whose USDT ATA still needs pre-creating, capped at
     *  {@code limit} and ordered by derivation index. */
    @Transactional(readOnly = true)
    public List<FederationPlayerWallet> walletsNeedingAta(UUID federationId, int limit) {
        return repository.findNeedingAta(federationId, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<FederationPlayerWallet> walletsByIds(UUID federationId, java.util.Collection<UUID> ids) {
        return repository.findByFederationIdAndIdIn(federationId, ids);
    }

    /** Mark a batch of wallets' ATAs created on-chain (after the create batch confirms). Returns the count updated. */
    @Transactional
    public int markAtaProvisioned(UUID federationId, java.util.Collection<UUID> ids) {
        int n = ids.isEmpty() ? 0 : repository.updateAtaProvisioned(federationId, ids, true);
        log.info("Federation {} — marked {} wallet ATA(s) provisioned", federationId, n);
        return n;
    }

    /** Mark a batch of wallets' ATAs closed (rent reclaimed) — they can no longer receive deposits. */
    @Transactional
    public int markAtaClosed(UUID federationId, java.util.Collection<UUID> ids) {
        int n = ids.isEmpty() ? 0 : repository.updateAtaProvisioned(federationId, ids, false);
        log.info("Federation {} — marked {} wallet ATA(s) closed", federationId, n);
        return n;
    }

    /** Pool dashboard: per-status counts, ATA-provisioned count and the total on-chain buy-in collected. */
    @Transactional(readOnly = true)
    public com.truholdem.dto.FederationWalletStatsResponse stats(UUID federationId) {
        long free = repository.countByFederationIdAndStatus(federationId, FederationWalletStatus.FREE);
        long assigned = repository.countByFederationIdAndStatus(federationId, FederationWalletStatus.ASSIGNED);
        long funded = repository.countByFederationIdAndStatus(federationId, FederationWalletStatus.FUNDED);
        long refunded = repository.countByFederationIdAndStatus(federationId, FederationWalletStatus.REFUNDED);
        long provisioned = repository.countByFederationIdAndAtaProvisionedTrue(federationId);
        java.math.BigDecimal fundedAmount = repository.sumFundedAmount(federationId);
        return new com.truholdem.dto.FederationWalletStatsResponse(
                free + assigned + funded + refunded, free, assigned, funded, refunded, provisioned,
                fundedAmount == null ? java.math.BigDecimal.ZERO : fundedAmount);
    }

    private static void requireValid(String address, String field) {
        if (address == null || address.length() > MAX_ADDRESS_LENGTH || !SolKeys.isValidAddress(address)) {
            throw new IllegalArgumentException("Invalid base58 " + field + ": " + address);
        }
    }
}
