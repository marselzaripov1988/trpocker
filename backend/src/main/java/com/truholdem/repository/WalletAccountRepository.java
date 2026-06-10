package com.truholdem.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WalletAccount;

@Repository
public interface WalletAccountRepository extends JpaRepository<WalletAccount, UUID> {

    Optional<WalletAccount> findByUserIdAndAsset(UUID userId, CryptoAsset asset);

    List<WalletAccount> findByUserId(UUID userId);

    /**
     * Total user balance held for one asset — the platform's aggregate liability to its users for that asset
     * (what we owe). The solvency monitor compares this against the operator-declared on-chain reserve float.
     * {@code COALESCE(..,0)} so an asset with no accounts yields zero rather than null.
     */
    @Query("SELECT COALESCE(SUM(a.balance), 0) FROM WalletAccount a WHERE a.asset = :asset")
    BigDecimal sumBalanceByAsset(@Param("asset") CryptoAsset asset);
}
