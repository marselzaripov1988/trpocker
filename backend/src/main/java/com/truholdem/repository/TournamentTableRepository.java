package com.truholdem.repository;

import com.truholdem.model.TournamentTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TournamentTableRepository extends JpaRepository<TournamentTable, UUID> {

    List<TournamentTable> findByTournamentId(UUID tournamentId);
    
    @Query("SELECT t FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isActive = true ORDER BY t.tableNumber ASC")
    List<TournamentTable> findActiveTablesByTournament(@Param("tournamentId") UUID tournamentId);
    
    @Query("SELECT t FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isFinalTable = true")
    Optional<TournamentTable> findFinalTableByTournament(@Param("tournamentId") UUID tournamentId);
    
    Optional<TournamentTable> findByTournamentIdAndTableNumber(UUID tournamentId, int tableNumber);
    
    @Query("SELECT COUNT(t) FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isActive = true")
    int countActiveTablesByTournament(@Param("tournamentId") UUID tournamentId);
    
    @Query("SELECT t FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isActive = true ORDER BY SIZE(t.playerIds) ASC")
    List<TournamentTable> findActiveTablesOrderByPlayerCountAsc(@Param("tournamentId") UUID tournamentId);
    
    @Query("SELECT t FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isActive = true ORDER BY SIZE(t.playerIds) DESC")
    List<TournamentTable> findActiveTablesOrderByPlayerCountDesc(@Param("tournamentId") UUID tournamentId);
    
    
    @Query("SELECT t FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND :playerId MEMBER OF t.playerIds")
    Optional<TournamentTable> findByTournamentIdAndPlayerId(@Param("tournamentId") UUID tournamentId, @Param("playerId") UUID playerId);
    
    
    @Query("SELECT t FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isActive = true AND SIZE(t.playerIds) < 9 ORDER BY SIZE(t.playerIds) ASC")
    List<TournamentTable> findTablesWithEmptySeats(@Param("tournamentId") UUID tournamentId);
    
    
    @Query("SELECT SUM(SIZE(t.playerIds)) FROM TournamentTable t WHERE t.tournament.id = :tournamentId AND t.isActive = true")
    Long countTotalPlayersAtTables(@Param("tournamentId") UUID tournamentId);

    @Query("SELECT t FROM TournamentTable t JOIN FETCH t.tournament WHERE t.currentGame.id = :gameId")
    Optional<TournamentTable> findByCurrentGameId(@Param("gameId") UUID gameId);

    @Query("""
            SELECT t FROM TournamentTable t
            JOIN FETCH t.tournament
            LEFT JOIN FETCH t.currentGame
            WHERE t.id = :tableId AND t.tournament.id = :tournamentId
            """)
    Optional<TournamentTable> findByIdAndTournamentIdWithDetails(
            @Param("tableId") UUID tableId,
            @Param("tournamentId") UUID tournamentId);
}
