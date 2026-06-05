package com.truholdem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.PyramidBuyout;

@Repository
public interface PyramidBuyoutRepository extends JpaRepository<PyramidBuyout, UUID> {

    List<PyramidBuyout> findByTournamentId(UUID tournamentId);

    Optional<PyramidBuyout> findByTournamentIdAndBuyerPlayerId(UUID tournamentId, UUID buyerPlayerId);

    boolean existsByTournamentIdAndBuyerPlayerId(UUID tournamentId, UUID buyerPlayerId);

    boolean existsByTournamentIdAndLevelAndSeatIndex(UUID tournamentId, int level, int seatIndex);

    int countByTournamentId(UUID tournamentId);
}
