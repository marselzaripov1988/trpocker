package com.truholdem.service.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.dto.wallet.PoolEntryDto;
import com.truholdem.dto.wallet.PoolImportResponse;
import com.truholdem.dto.wallet.PoolStatusResponse;
import com.truholdem.dto.wallet.PoolStatusResponse.AssetCount;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressPoolEntry;
import com.truholdem.model.DepositAddressStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.service.wallet.WalletExceptions.DepositAddressPoolExhaustedException;
import com.truholdem.service.wallet.crypto.BtcKeys;
import com.truholdem.service.wallet.crypto.EthKeys;
import com.truholdem.service.wallet.crypto.TronKeys;

/**
 * Serves watch-only deposit addresses from a pool that was generated OFFLINE (private keys + seed never touch
 * this server). Imports a batch of public addresses, then hands them out one-per-user-per-asset. Allocation
 * is idempotent (a user always gets back the same address) and concurrency-safe (the next free address is
 * row-locked while claimed, so two simultaneous registrations cannot grab the same one).
 */
@Service
public class DepositAddressPoolService {

    private static final Logger log = LoggerFactory.getLogger(DepositAddressPoolService.class);
    private static final int MAX_ADDRESS_LENGTH = 128;

    private final DepositAddressPoolRepository repository;

    public DepositAddressPoolService(DepositAddressPoolRepository repository) {
        this.repository = repository;
    }

    /** Return the user's deposit address for the asset — the existing one if already allocated, otherwise
     *  claim the next free address from the pool. Throws if the pool for that asset is empty. */
    @Transactional
    public String allocate(UUID userId, CryptoAsset asset) {
        Optional<DepositAddressPoolEntry> existing = repository.findByAssetAndAssignedUserId(asset, userId);
        if (existing.isPresent()) {
            return existing.get().getAddress();
        }
        DepositAddressPoolEntry free = repository
                .findFirstByAssetAndStatusOrderByDerivationIndexAsc(asset, DepositAddressStatus.FREE)
                .orElseThrow(() -> new DepositAddressPoolExhaustedException(asset));
        free.assignTo(userId);
        repository.save(free);
        log.info("Allocated pooled {} deposit address #{} to user {}", asset, free.getDerivationIndex(), userId);
        return free.getAddress();
    }

    /** Import a batch of offline-generated public addresses. Validates format (EIP-55 for ETH-family assets),
     *  skips already-imported addresses (idempotent re-import), and inserts the rest as FREE. */
    @Transactional
    public PoolImportResponse importBatch(List<PoolEntryDto> entries) {
        int imported = 0;
        int skipped = 0;
        for (PoolEntryDto e : entries) {
            String address = e.address();
            if (address == null || address.isBlank() || address.length() > MAX_ADDRESS_LENGTH) {
                throw new IllegalArgumentException("Invalid address: " + address);
            }
            if (isEthereumAddress(e.asset()) && !EthKeys.isValidChecksumAddress(address)) {
                throw new IllegalArgumentException(
                        "Bad EIP-55 checksum for " + e.asset() + " address: " + address);
            }
            if (isTronAddress(e.asset()) && !TronKeys.isValidAddress(address)) {
                throw new IllegalArgumentException(
                        "Invalid TRON (Base58Check) address for " + e.asset() + ": " + address);
            }
            if (isBitcoinAddress(e.asset()) && !BtcKeys.isValidAddress(address)) {
                throw new IllegalArgumentException(
                        "Invalid Bitcoin address for " + e.asset() + " (expected P2PKH 1… or SegWit bc1q…): "
                                + address);
            }
            if (repository.existsByAssetAndAddress(e.asset(), address)) {
                skipped++;
                continue;
            }
            repository.save(new DepositAddressPoolEntry(e.asset(), address, e.derivationIndex()));
            imported++;
        }
        log.info("Deposit-address-pool import: {} added, {} skipped (duplicates)", imported, skipped);
        return new PoolImportResponse(imported, skipped);
    }

    /** Free/assigned counts per asset (assets with no rows are omitted) for low-watermark monitoring. */
    @Transactional(readOnly = true)
    public PoolStatusResponse status() {
        List<AssetCount> counts = new ArrayList<>();
        for (CryptoAsset asset : CryptoAsset.values()) {
            long free = repository.countByAssetAndStatus(asset, DepositAddressStatus.FREE);
            long assigned = repository.countByAssetAndStatus(asset, DepositAddressStatus.ASSIGNED);
            if (free > 0 || assigned > 0) {
                counts.add(new AssetCount(asset, free, assigned));
            }
        }
        return new PoolStatusResponse(counts);
    }

    /** ETH and all ERC-20 tokens share the same Ethereum address format → validate via EIP-55 checksum. */
    private static boolean isEthereumAddress(CryptoAsset asset) {
        return "ETH".equals(asset.getNetwork()) || "ERC20".equals(asset.getNetwork());
    }

    /** TRC-20 tokens use a TRON Base58Check address → validate prefix + checksum. */
    private static boolean isTronAddress(CryptoAsset asset) {
        return "TRC20".equals(asset.getNetwork());
    }

    /** Bitcoin uses a legacy P2PKH Base58Check address → validate version + checksum. */
    private static boolean isBitcoinAddress(CryptoAsset asset) {
        return "BTC".equals(asset.getNetwork());
    }
}
