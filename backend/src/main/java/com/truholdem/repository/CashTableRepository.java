package com.truholdem.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CashTable;

@Repository
public interface CashTableRepository extends JpaRepository<CashTable, UUID> {

    List<CashTable> findByActiveTrueOrderBySmallBlindAsc();
}
