package com.truholdem.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.KycDocument;

@Repository
public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    Optional<KycDocument> findFirstByUserIdOrderByUploadedAtDesc(UUID userId);

    List<KycDocument> findByUserId(UUID userId);

    List<KycDocument> findByUploadedAtBefore(Instant cutoff);
}
