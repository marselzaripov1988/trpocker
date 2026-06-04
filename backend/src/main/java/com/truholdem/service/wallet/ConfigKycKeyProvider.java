package com.truholdem.service.wallet;

import java.util.Base64;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;

/**
 * KYC key provider backed by the config keyring ({@code app.payments.kyc-encryption-keys} + active id), with
 * backward compatibility for the legacy single key (exposed as id {@code "default"}). New uploads use the
 * active key id; reads resolve whichever key id the document recorded — so rotating keys (add a new key, flip
 * the active id) leaves older documents decryptable. The default provider (unless
 * {@code app.payments.kyc-key-provider=kms}).
 */
@Component
@ConditionalOnProperty(name = "app.payments.kyc-key-provider", havingValue = "config", matchIfMissing = true)
public class ConfigKycKeyProvider implements KycKeyProvider {

    /** Key id assigned to the legacy single-key config (and to documents predating per-document key ids). */
    public static final String LEGACY_KEY_ID = "default";

    private final AppProperties appProperties;

    public ConfigKycKeyProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public Optional<DataKey> newDataKey() {
        return activeKeyId().map(id -> new DataKey(resolveKey(id), id));
    }

    /** Key id used to encrypt new uploads, or empty if encryption is disabled. */
    public Optional<String> activeKeyId() {
        AppProperties.Payments p = appProperties.getPayments();
        String active = p.getKycActiveKeyId();
        if (active != null && !active.isBlank() && p.getKycEncryptionKeys().containsKey(active)) {
            return Optional.of(active);
        }
        if (p.getKycEncryptionKey() != null && !p.getKycEncryptionKey().isBlank()) {
            return Optional.of(LEGACY_KEY_ID); // legacy single key
        }
        return Optional.empty(); // encryption disabled
    }

    @Override
    public byte[] resolveKey(String keyId) {
        AppProperties.Payments p = appProperties.getPayments();
        String b64 = p.getKycEncryptionKeys().get(keyId);
        if (b64 == null && LEGACY_KEY_ID.equals(keyId)) {
            b64 = p.getKycEncryptionKey(); // legacy fallback
        }
        if (b64 == null || b64.isBlank()) {
            throw new IllegalStateException("No KYC encryption key configured for id '" + keyId + "'");
        }
        byte[] key = Base64.getDecoder().decode(b64.trim());
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalStateException(
                    "KYC encryption key '" + keyId + "' must be a base64 AES key of 16/24/32 bytes");
        }
        return key;
    }
}
