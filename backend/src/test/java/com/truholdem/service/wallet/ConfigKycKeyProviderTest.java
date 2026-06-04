package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.config.AppProperties;

@DisplayName("ConfigKycKeyProvider (keyring + legacy single key)")
class ConfigKycKeyProviderTest {

    private static String key(byte b) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, b);
        return Base64.getEncoder().encodeToString(k);
    }

    private static ConfigKycKeyProvider provider(AppProperties props) {
        return new ConfigKycKeyProvider(props);
    }

    @Test
    @DisplayName("no keys configured → encryption disabled (empty active id)")
    void encryptionDisabled() {
        AppProperties props = new AppProperties();
        assertThat(provider(props).activeKeyId()).isEmpty();
    }

    @Test
    @DisplayName("legacy single key → active id is \"default\" and resolves to that key")
    void legacyKey() {
        AppProperties props = new AppProperties();
        props.getPayments().setKycEncryptionKey(key((byte) 1));

        ConfigKycKeyProvider p = provider(props);

        assertThat(p.activeKeyId()).contains(ConfigKycKeyProvider.LEGACY_KEY_ID);
        assertThat(p.resolveKey(ConfigKycKeyProvider.LEGACY_KEY_ID)).hasSize(32);
    }

    @Test
    @DisplayName("keyring + active id → active id is used; all ids resolve")
    void keyringActive() {
        AppProperties props = new AppProperties();
        props.getPayments().getKycEncryptionKeys().put("2026q1", key((byte) 7));
        props.getPayments().getKycEncryptionKeys().put("2026q2", key((byte) 9));
        props.getPayments().setKycActiveKeyId("2026q2");

        ConfigKycKeyProvider p = provider(props);

        assertThat(p.activeKeyId()).contains("2026q2");
        assertThat(p.resolveKey("2026q1")).containsOnly((byte) 7);
        assertThat(p.resolveKey("2026q2")).containsOnly((byte) 9);
    }

    @Test
    @DisplayName("active id not in the keyring falls back to legacy key if present, else disabled")
    void activeIdMissing() {
        AppProperties withLegacy = new AppProperties();
        withLegacy.getPayments().setKycActiveKeyId("ghost");
        withLegacy.getPayments().setKycEncryptionKey(key((byte) 3));
        assertThat(provider(withLegacy).activeKeyId()).contains(ConfigKycKeyProvider.LEGACY_KEY_ID);

        AppProperties noLegacy = new AppProperties();
        noLegacy.getPayments().setKycActiveKeyId("ghost");
        assertThat(provider(noLegacy).activeKeyId()).isEmpty();
    }

    @Test
    @DisplayName("unknown key id throws")
    void unknownKeyId() {
        AppProperties props = new AppProperties();
        props.getPayments().getKycEncryptionKeys().put("k1", key((byte) 1));
        assertThatThrownBy(() -> provider(props).resolveKey("nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("a non-AES-length key is rejected")
    void badKeyLength() {
        AppProperties props = new AppProperties();
        props.getPayments().getKycEncryptionKeys().put("k1", Base64.getEncoder().encodeToString(new byte[7]));
        assertThatThrownBy(() -> provider(props).resolveKey("k1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16/24/32");
    }
}
