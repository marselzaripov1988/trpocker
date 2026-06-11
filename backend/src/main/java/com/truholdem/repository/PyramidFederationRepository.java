package com.truholdem.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;

public interface PyramidFederationRepository extends JpaRepository<PyramidFederation, UUID> {

    List<PyramidFederation> findByStatus(FederationStatus status);

    /** Isolated-custody federations in a status — the deposit poller scans REGISTERING ones for on-chain buy-ins. */
    List<PyramidFederation> findByStatusAndIsolatedWalletsEnabledTrue(FederationStatus status);
}
