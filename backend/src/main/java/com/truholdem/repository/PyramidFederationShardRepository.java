package com.truholdem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.PyramidFederationShard;

public interface PyramidFederationShardRepository extends JpaRepository<PyramidFederationShard, UUID> {

    List<PyramidFederationShard> findByFederationIdOrderByShardIndexAsc(UUID federationId);

    Optional<PyramidFederationShard> findByFederationIdAndShardIndex(UUID federationId, int shardIndex);

    List<PyramidFederationShard> findByFederationIdAndStatus(UUID federationId, FederationShardStatus status);

    int countByFederationIdAndStatus(UUID federationId, FederationShardStatus status);

    Optional<PyramidFederationShard> findByTournamentId(UUID tournamentId);
}
