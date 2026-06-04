package com.truholdem.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;

@Repository
public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, UUID> {

    List<WithdrawalRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<WithdrawalRequest> findByStatusOrderByCreatedAtAsc(WithdrawalStatus status);

    List<WithdrawalRequest> findByStatusInOrderByCreatedAtAsc(Collection<WithdrawalStatus> statuses);

    List<WithdrawalRequest> findByUserIdAndAssetAndCreatedAtAfter(UUID userId, CryptoAsset asset, Instant after);
}
