package com.truholdem.repository;

import com.truholdem.model.GameEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read/append access to the append-only domain-event log (Phase 4). Events are ordered globally by the
 * {@code seqNo} identity (the primary key), which equals publication order.
 */
@Repository
public interface GameEventLogRepository extends JpaRepository<GameEventLog, Long> {

    List<GameEventLog> findByGameIdOrderBySeqNoAsc(UUID gameId);

    List<GameEventLog> findByGameIdAndHandNumberOrderBySeqNoAsc(UUID gameId, int handNumber);

    long countByGameId(UUID gameId);
}
