package com.truholdem.tools;

import java.math.BigInteger;
import java.util.HexFormat;

import com.truholdem.service.wallet.crypto.EthKeys;
import com.truholdem.service.wallet.crypto.EthTransactionSigner;

/**
 * AIR-GAPPED withdrawal signer — run on the offline machine that holds the seed, NOT on the server. Takes an
 * exported withdrawal intent (from {@code GET /v1/admin/wallet/withdrawals/{id}/unsigned}) plus the chain
 * parameters (nonce/gas/chainId, which the operator reads from a node), derives the signing key from the seed
 * by index (same KDF as the deposit-address pool), and prints a signed raw Ethereum transaction. Broadcast it
 * with any node's {@code eth_sendRawTransaction}, then record the resulting tx hash back via
 * {@code POST /v1/admin/wallet/withdrawals/{id}/broadcast}.
 *
 * <p>Lives in test sources so signing capability never ships in the server jar. Ethereum / ERC-20 only;
 * BTC PSBT signing is a separate tool.
 *
 * <p>Usage: {@code java -cp <test-classpath> com.truholdem.tools.OfflineWithdrawalSigner
 * --seed-hex=<hex> --index=<n> --to=0x<recipient> --amount-wei=<int> --nonce=<n> --gas-price-wei=<int>
 * --gas-limit=<n> --chain-id=<n> [--token=0x<erc20-contract>]}
 */
public final class OfflineWithdrawalSigner {

    private OfflineWithdrawalSigner() {
    }

    public static void main(String[] args) {
        byte[] seed = HexFormat.of().parseHex(req(args, "--seed-hex"));
        long index = Long.parseLong(req(args, "--index"));
        byte[] recipient = EthTransactionSigner.hexToBytes(req(args, "--to"));
        BigInteger amount = new BigInteger(req(args, "--amount-wei"));
        BigInteger nonce = new BigInteger(req(args, "--nonce"));
        BigInteger gasPrice = new BigInteger(req(args, "--gas-price-wei"));
        BigInteger gasLimit = new BigInteger(req(args, "--gas-limit"));
        long chainId = Long.parseLong(req(args, "--chain-id"));
        String token = opt(args, "--token");

        BigInteger privateKey = EthKeys.derivePrivateKey(seed, "eth/" + index);

        byte[] to;
        BigInteger value;
        byte[] data;
        if (token != null) { // ERC-20 transfer (e.g. USDT-ERC20): call the token contract
            to = EthTransactionSigner.hexToBytes(token);
            value = BigInteger.ZERO;
            data = EthTransactionSigner.erc20TransferData(recipient, amount);
        } else { // native ETH transfer
            to = recipient;
            value = amount;
            data = new byte[0];
        }

        String rawTx = EthTransactionSigner.signLegacyTransaction(
                privateKey, nonce, gasPrice, gasLimit, to, value, data, chainId);

        System.out.println("Signed raw transaction (broadcast via eth_sendRawTransaction):");
        System.out.println(rawTx);
        System.out.println("From address: " + EthKeys.addressFromPrivateKey(privateKey));
    }

    private static String req(String[] args, String name) {
        String v = opt(args, name);
        if (v == null) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return v;
    }

    private static String opt(String[] args, String name) {
        for (String a : args) {
            if (a.startsWith(name + "=")) {
                return a.substring(name.length() + 1);
            }
        }
        return null;
    }
}
