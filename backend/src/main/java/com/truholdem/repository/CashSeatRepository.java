package com.truholdem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;

@Repository
public interface CashSeatRepository extends JpaRepository<CashSeat, UUID> {

    List<CashSeat> findByCashTableId(UUID cashTableId);

    List<CashSeat> findByCashTableIdAndStatus(UUID cashTableId, CashSeatStatus status);

    Optional<CashSeat> findByCashTableIdAndPlayerIdAndStatusNot(UUID cashTableId, UUID playerId,
            CashSeatStatus status);

    boolean existsByCashTableIdAndSeatNumberAndStatusNot(UUID cashTableId, int seatNumber, CashSeatStatus status);

    long countByCashTableIdAndStatusNot(UUID cashTableId, CashSeatStatus status);
}
