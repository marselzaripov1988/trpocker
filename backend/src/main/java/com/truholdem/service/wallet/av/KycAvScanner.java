package com.truholdem.service.wallet.av;

/** Antivirus scan for KYC uploads. Throws if the content is rejected (infected) or the scan can't complete. */
public interface KycAvScanner {

    void scan(byte[] content);
}
