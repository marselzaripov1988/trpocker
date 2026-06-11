package com.truholdem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.FederationRefund;
import com.truholdem.model.FederationRefundStatus;

@Repository
public interface FederationRefundRepository extends JpaRepository<FederationRefund, UUID> {

    List<FederationRefund> findByFederationIdAndStatus(UUID federationId, FederationRefundStatus status);

    boolean existsByFederationIdAndWalletId(UUID federationId, UUID walletId);

    Optional<FederationRefund> findByFederationIdAndWalletId(UUID federationId, UUID walletId);

    List<FederationRefund> findByStatusOrderByCreatedAtAsc(FederationRefundStatus status);
}
