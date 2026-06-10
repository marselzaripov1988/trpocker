package com.truholdem.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    long countByStatus(WithdrawalStatus status);

    long countByStatusIn(Collection<WithdrawalStatus> statuses);

    /**
     * Total amount of withdrawals for one asset currently in the given (non-terminal) states — the funds the
     * platform is committed to paying out but has not yet settled on-chain. The solvency monitor adds this to
     * the standing liabilities so the float comparison accounts for in-flight payouts, not just resting balances.
     */
    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WithdrawalRequest w WHERE w.asset = :asset AND w.status IN :statuses")
    BigDecimal sumAmountByAssetAndStatusIn(@Param("asset") CryptoAsset asset,
            @Param("statuses") Collection<WithdrawalStatus> statuses);
}
