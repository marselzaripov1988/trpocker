package com.truholdem.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationWalletStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface FederationPlayerWalletRepository extends JpaRepository<FederationPlayerWallet, UUID> {

    /** Lowest-index wallet in a status for a federation, row-locked (SELECT … FOR UPDATE) so two concurrent
     *  assignments never claim the same one. Must be called inside a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FederationPlayerWallet> findFirstByFederationIdAndStatusOrderByDerivationIndexAsc(
            UUID federationId, FederationWalletStatus status);

    /** The wallet already assigned to this player in the federation (idempotent re-allocation). */
    Optional<FederationPlayerWallet> findByFederationIdAndAssignedPlayerId(UUID federationId, UUID playerId);

    Optional<FederationPlayerWallet> findByFederationIdAndAddress(UUID federationId, String address);

    boolean existsByFederationIdAndAddress(UUID federationId, String address);

    List<FederationPlayerWallet> findByFederationIdAndStatus(UUID federationId, FederationWalletStatus status);

    long countByFederationIdAndStatus(UUID federationId, FederationWalletStatus status);

    /** The subset of {@code addresses} already imported for the federation — for bulk idempotent import. */
    @Query("SELECT w.address FROM FederationPlayerWallet w "
            + "WHERE w.federationId = :federationId AND w.address IN :addresses")
    List<String> findExistingAddresses(@Param("federationId") UUID federationId,
            @Param("addresses") Collection<String> addresses);
}
