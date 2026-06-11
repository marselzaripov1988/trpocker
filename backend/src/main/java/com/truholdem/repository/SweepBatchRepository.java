package com.truholdem.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.SweepBatch;
import com.truholdem.model.SweepBatchStatus;

@Repository
public interface SweepBatchRepository extends JpaRepository<SweepBatch, UUID> {

    /** Sweeps in a given lifecycle state, oldest first — used by the reconcile sweep (later slice). */
    List<SweepBatch> findByStatusOrderByCreatedAtAsc(SweepBatchStatus status);
}
