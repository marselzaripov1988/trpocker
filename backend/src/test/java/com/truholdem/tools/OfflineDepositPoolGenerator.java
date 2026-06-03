package com.truholdem.tools;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.model.CryptoAsset;
import com.truholdem.service.wallet.crypto.BtcKeys;
import com.truholdem.service.wallet.crypto.EthKeys;
import com.truholdem.service.wallet.crypto.TronKeys;

/**
 * OFFLINE deposit-address pool generator — run this on an air-gapped machine, NOT on the server.
 *
 * <p>Deliberately lives in test sources so it is excluded from the production jar (a private-key generator
 * must never ship inside the online service). It derives a batch of ETH-family keypairs from a single seed
 * (reusing {@link EthKeys}; backup = the one seed + index range) and writes two files:
 * <ul>
 *   <li>{@code private.json} — seed + per-index private keys + addresses. KEEP OFFLINE (encrypt, cold storage).</li>
 *   <li>{@code addresses.json} — only the public addresses, shaped as the admin import body
 *       ({@code {"addresses":[{asset,derivationIndex,address}]}}). Carry this to the server.</li>
 * </ul>
 *
 * <p>Usage: {@code java -cp <classpath> com.truholdem.tools.OfflineDepositPoolGenerator
 * --count=1000 [--asset=ETH|USDT_ERC20|USDT_TRC20|BTC] [--btc-style=p2pkh|bech32] [--seed-hex=<64hex>]
 * [--out-dir=.]}
 *
 * <p>Supports the Ethereum family (ETH + ERC-20 tokens share one address), TRON (TRC-20, {@code T…}
 * Base58Check) and Bitcoin (legacy P2PKH {@code 1…} or native SegWit P2WPKH {@code bc1q…} via
 * {@code --btc-style=bech32}). Each chain derives under a separate label, so the key sets never overlap.
 */
public final class OfflineDepositPoolGenerator {

    private OfflineDepositPoolGenerator() {
    }

    public record Keypair(long index, String privateKeyHex, String address) {
    }

    public record PublicEntry(CryptoAsset asset, long derivationIndex, String address) {
    }

    public record Batch(String seedHex, CryptoAsset asset, List<Keypair> keys) {
        public List<PublicEntry> publicEntries() {
            List<PublicEntry> out = new ArrayList<>(keys.size());
            for (Keypair k : keys) {
                out.add(new PublicEntry(asset, k.index(), k.address()));
            }
            return out;
        }
    }

    /** Deterministically derive {@code count} keypairs from {@code seed} (index 0..count-1). Supports the
     *  Ethereum family (ETH, ERC-20 tokens — shared address) and TRON (TRC-20). A per-chain derivation label
     *  keeps the ETH and TRON key sets independent (no key reuse across chains). */
    /** Bitcoin address style: legacy P2PKH ({@code 1…}) or native SegWit P2WPKH ({@code bc1q…}). */
    public enum BtcStyle {
        P2PKH, BECH32
    }

    public static Batch generate(byte[] seed, CryptoAsset asset, int count) {
        return generate(seed, asset, count, BtcStyle.P2PKH);
    }

    public static Batch generate(byte[] seed, CryptoAsset asset, int count, BtcStyle btcStyle) {
        String network = asset.getNetwork();
        boolean eth = "ETH".equals(network) || "ERC20".equals(network);
        boolean tron = "TRC20".equals(network);
        boolean btc = "BTC".equals(network);
        if (!eth && !tron && !btc) {
            throw new IllegalArgumentException("Unsupported network for this generator: " + asset);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        String label = eth ? "eth/" : tron ? "tron/" : "btc/";
        List<Keypair> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigInteger priv = EthKeys.derivePrivateKey(seed, label + i);
            String address;
            if (eth) {
                address = EthKeys.addressFromPrivateKey(priv);
            } else if (tron) {
                address = TronKeys.addressFromPrivateKey(priv);
            } else {
                address = btcStyle == BtcStyle.BECH32 ? BtcKeys.p2wpkhAddress(priv) : BtcKeys.p2pkhAddress(priv);
            }
            keys.add(new Keypair(i, priv.toString(16), address));
        }
        return new Batch(HexFormat.of().formatHex(seed), asset, keys);
    }

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(argValue(args, "--count", "1000"));
        CryptoAsset asset = CryptoAsset.valueOf(argValue(args, "--asset", "ETH"));
        String seedHex = argValue(args, "--seed-hex", null);
        Path outDir = Path.of(argValue(args, "--out-dir", "."));
        BtcStyle btcStyle = BtcStyle.valueOf(argValue(args, "--btc-style", "P2PKH").toUpperCase());

        byte[] seed;
        if (seedHex != null) {
            seed = HexFormat.of().parseHex(seedHex);
        } else {
            seed = new byte[32];
            new SecureRandom().nextBytes(seed);
        }

        Batch batch = generate(seed, asset, count, btcStyle);
        ObjectMapper json = new ObjectMapper();
        Files.createDirectories(outDir);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("private.json").toFile(), batch);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("addresses.json").toFile(), new ImportFile(batch.publicEntries()));

        System.out.printf("Generated %d %s addresses.%n", count, asset);
        System.out.printf("  private.json   -> %s  (KEEP OFFLINE — contains seed + private keys)%n",
                outDir.resolve("private.json").toAbsolutePath());
        System.out.printf("  addresses.json -> %s  (import to the server)%n",
                outDir.resolve("addresses.json").toAbsolutePath());
    }

    /** Mirrors the admin PoolImportRequest body so addresses.json is POST-ready. */
    private record ImportFile(List<PublicEntry> addresses) {
    }

    private static String argValue(String[] args, String name, String def) {
        for (String a : args) {
            if (a.startsWith(name + "=")) {
                return a.substring(name.length() + 1);
            }
        }
        return def;
    }
}
