package com.truholdem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressPoolEntry;
import com.truholdem.model.DepositAddressStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface DepositAddressPoolRepository extends JpaRepository<DepositAddressPoolEntry, UUID> {

    /** Lowest-index free address for an asset, row-locked (SELECT … FOR UPDATE) so two concurrent
     *  allocations never claim the same address. Must be called inside a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DepositAddressPoolEntry> findFirstByAssetAndStatusOrderByDerivationIndexAsc(
            CryptoAsset asset, DepositAddressStatus status);

    /** The address already assigned to this user for the asset (idempotent re-allocation). */
    Optional<DepositAddressPoolEntry> findByAssetAndAssignedUserId(CryptoAsset asset, UUID assignedUserId);

    boolean existsByAssetAndAddress(CryptoAsset asset, String address);

    Optional<DepositAddressPoolEntry> findByAssetAndAddress(CryptoAsset asset, String address);

    long countByAssetAndStatus(CryptoAsset asset, DepositAddressStatus status);

    /** All addresses for an asset in a given status — used by the sweep coordinator to enumerate the ASSIGNED
     *  deposit addresses whose UTXOs are consolidated into the treasury. */
    List<DepositAddressPoolEntry> findByAssetAndStatus(CryptoAsset asset, DepositAddressStatus status);
}
