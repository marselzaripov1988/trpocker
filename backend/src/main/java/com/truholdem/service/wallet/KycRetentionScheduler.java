package com.truholdem.service.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;

/**
 * GDPR retention sweep: periodically deletes KYC verification media older than
 * {@code app.payments.kyc-retention-days}. Inert unless payments are enabled and a storage dir + positive
 * retention are configured. Idempotent (file deletes use deleteIfExists), so it is safe to run on every node
 * in a cluster.
 */
@Component
public class KycRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(KycRetentionScheduler.class);

    private final KycVerificationService kycVerificationService;
    private final AppProperties appProperties;

    public KycRetentionScheduler(KycVerificationService kycVerificationService, AppProperties appProperties) {
        this.kycVerificationService = kycVerificationService;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${app.payments.kyc-retention-scan-interval-ms:86400000}")
    public void purgeExpired() {
        AppProperties.Payments p = appProperties.getPayments();
        if (!p.isEnabled() || p.getKycStorageDir() == null || p.getKycStorageDir().isBlank()) {
            return;
        }
        kycVerificationService.retentionCutoff().ifPresent(cutoff -> {
            try {
                kycVerificationService.purgeOlderThan(cutoff);
            } catch (RuntimeException e) {
                log.warn("KYC retention sweep failed", e);
            }
        });
    }
}
