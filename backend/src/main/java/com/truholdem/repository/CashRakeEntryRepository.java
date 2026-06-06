package com.truholdem.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CashRakeEntry;

@Repository
public interface CashRakeEntryRepository extends JpaRepository<CashRakeEntry, UUID> {

    Optional<CashRakeEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<CashRakeEntry> findByCashTableId(UUID cashTableId);

    /** Total rake (house revenue) accrued at a table; {@code null}/zero when nothing has been raked yet. */
    @Query("select coalesce(sum(e.rakeAmount), 0) from CashRakeEntry e where e.cashTableId = :cashTableId")
    BigDecimal totalRakeForTable(@Param("cashTableId") UUID cashTableId);
}
