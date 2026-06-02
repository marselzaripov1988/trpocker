package com.truholdem.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WalletLedgerEntry;

@Repository
public interface WalletLedgerEntryRepository extends JpaRepository<WalletLedgerEntry, UUID> {

    boolean existsByExternalTxId(String externalTxId);

    List<WalletLedgerEntry> findByUserIdAndAssetOrderByCreatedAtDesc(UUID userId, CryptoAsset asset);
}
