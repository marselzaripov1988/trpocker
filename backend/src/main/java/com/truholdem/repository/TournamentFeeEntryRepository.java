package com.truholdem.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.TournamentFeeEntry;

@Repository
public interface TournamentFeeEntryRepository extends JpaRepository<TournamentFeeEntry, UUID> {

    Optional<TournamentFeeEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<TournamentFeeEntry> findBySourceId(UUID sourceId);

    /** Total house commission collected for one asset; {@code null}/zero when nothing has been raked yet. */
    @Query("select coalesce(sum(e.feeAmount), 0) from TournamentFeeEntry e where e.asset = :asset")
    BigDecimal totalFeeForAsset(@Param("asset") CryptoAsset asset);
}
