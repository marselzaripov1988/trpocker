package com.truholdem.service.wallet.av;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No-op scanner — active when AV scanning is disabled (default). */
@Component
@ConditionalOnProperty(name = "app.payments.kyc-av-scan-enabled", havingValue = "false", matchIfMissing = true)
public class NoopKycAvScanner implements KycAvScanner {

    @Override
    public void scan(byte[] content) {
        // scanning disabled
    }
}
