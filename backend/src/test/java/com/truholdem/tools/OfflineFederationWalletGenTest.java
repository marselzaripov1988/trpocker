package com.truholdem.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.service.wallet.crypto.SolKeys;
import com.truholdem.tools.OfflineDepositPoolGenerator.FedWallet;
import com.truholdem.tools.OfflineDepositPoolGenerator.FedWalletImportChunk;

/**
 * Offline 1M-scale keygen batching for isolated-custody federations: chunk generation is by ABSOLUTE index (so
 * chunks are consistent with a whole-field generation and re-runnable), keys are deterministic from the master
 * seed + federation id + index, and the chunked writer emits bounded-memory POST-ready import files.
 */
@DisplayName("OfflineDepositPoolGenerator — federation wallet batching")
class OfflineFederationWalletGenTest {

    private static final byte[] SEED = "fed-keygen-test-seed".getBytes(StandardCharsets.UTF_8);
    private static final UUID FED = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";

    @Test
    @DisplayName("a chunk equals the matching slice of a whole-field generation (consistent + re-runnable)")
    void chunkMatchesFullSlice() {
        List<FedWallet> full = OfflineDepositPoolGenerator.generateFederationWallets(SEED, FED, 10, MINT);
        List<FedWallet> chunk = OfflineDepositPoolGenerator.generateFederationWallets(SEED, FED, 3L, 4, MINT);

        assertThat(chunk).hasSize(4);
        for (int j = 0; j < 4; j++) {
            assertThat(chunk.get(j).index()).isEqualTo(3L + j);
            assertThat(chunk.get(j).ownerPubkey()).isEqualTo(full.get(3 + j).ownerPubkey());
            assertThat(chunk.get(j).address()).isEqualTo(full.get(3 + j).address());
        }
        // Re-running the same chunk is deterministic.
        assertThat(OfflineDepositPoolGenerator.generateFederationWallets(SEED, FED, 3L, 4, MINT))
                .containsExactlyElementsOf(chunk);
    }

    @Test
    @DisplayName("the owner key re-derives offline by seed + federation id + index")
    void ownerKeyReDerivable() {
        FedWallet w = OfflineDepositPoolGenerator.generateFederationWallets(SEED, FED, 5L, 1, MINT).get(0);
        byte[] reDerived = OfflineDepositPoolGenerator.federationWalletSeed(SEED, FED, 5L);
        assertThat(SolKeys.addressFromSeed(reDerived)).isEqualTo(w.ownerPubkey());
    }

    @Test
    @DisplayName("chunked writer emits bounded POST-ready import files + an offline secret")
    void writesChunkedImportFiles(@TempDir Path dir) throws Exception {
        int chunks = OfflineDepositPoolGenerator.writeFederationWalletsChunked(SEED, FED, MINT, 25, 10, dir);
        assertThat(chunks).isEqualTo(3); // 10 + 10 + 5

        assertThat(Files.exists(dir.resolve("fedwallets-secret.txt"))).isTrue();
        assertThat(Files.exists(dir.resolve("fedwallets-import-00000.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("fedwallets-import-00002.json"))).isTrue();

        FedWalletImportChunk first = new ObjectMapper().readValue(
                dir.resolve("fedwallets-import-00000.json").toFile(), FedWalletImportChunk.class);
        assertThat(first.federationId()).isEqualTo(FED.toString()); // file self-documents its tournament
        assertThat(first.wallets()).hasSize(10);
        assertThat(first.wallets().get(0).derivationIndex()).isZero();
        assertThat(first.wallets().get(9).derivationIndex()).isEqualTo(9L);
        assertThat(SolKeys.isValidAddress(first.wallets().get(0).address())).isTrue();

        FedWalletImportChunk last = new ObjectMapper().readValue(
                dir.resolve("fedwallets-import-00002.json").toFile(), FedWalletImportChunk.class);
        assertThat(last.wallets()).hasSize(5); // 20..24
        assertThat(last.wallets().get(0).derivationIndex()).isEqualTo(20L);
    }
}
