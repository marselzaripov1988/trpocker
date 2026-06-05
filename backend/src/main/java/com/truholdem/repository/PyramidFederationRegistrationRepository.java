package com.truholdem.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.truholdem.model.PyramidFederationRegistration;

public interface PyramidFederationRegistrationRepository
        extends JpaRepository<PyramidFederationRegistration, UUID> {

    List<PyramidFederationRegistration> findByFederationIdAndShardIndex(UUID federationId, int shardIndex);

    Optional<PyramidFederationRegistration> findByFederationIdAndPlayerId(UUID federationId, UUID playerId);

    boolean existsByFederationIdAndPlayerId(UUID federationId, UUID playerId);

    int countByFederationId(UUID federationId);
}
