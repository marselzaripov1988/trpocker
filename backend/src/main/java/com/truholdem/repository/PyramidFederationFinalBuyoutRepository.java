package com.truholdem.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.truholdem.model.PyramidFederationFinalBuyout;

public interface PyramidFederationFinalBuyoutRepository
        extends JpaRepository<PyramidFederationFinalBuyout, UUID> {

    List<PyramidFederationFinalBuyout> findByFederationId(UUID federationId);

    boolean existsByFederationIdAndBuyerPlayerId(UUID federationId, UUID buyerPlayerId);

    boolean existsByFederationIdAndShardIndex(UUID federationId, int shardIndex);

    int countByFederationId(UUID federationId);
}
