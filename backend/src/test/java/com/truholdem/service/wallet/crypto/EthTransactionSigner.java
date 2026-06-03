package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.util.HexFormat;

import com.truholdem.tools.signer.Rlp;

/**
 * Builds and signs an EIP-155 legacy Ethereum transaction entirely OFFLINE (no node): RLP-encode the fields,
 * Keccak-256 hash, sign with RFC-6979 ECDSA, and RLP-encode the signed tx. The output is a broadcastable
 * raw-tx hex (any node's {@code eth_sendRawTransaction}). Covers native ETH and ERC-20 transfers (USDT-ERC20).
 * Test-sources only — signing never ships in the server jar.
 */
public final class EthTransactionSigner {

    private static final byte[] ERC20_TRANSFER_SELECTOR = { (byte) 0xa9, (byte) 0x05, (byte) 0x9c, (byte) 0xbb };

    private EthTransactionSigner() {
    }

    /** Sign an EIP-155 legacy transaction; returns the 0x-prefixed raw tx hex. */
    public static String signLegacyTransaction(BigInteger privateKey, BigInteger nonce, BigInteger gasPrice,
            BigInteger gasLimit, byte[] to, BigInteger value, byte[] data, long chainId) {
        byte[] signingPayload = Rlp.encodeList(
                Rlp.encodeBigInteger(nonce), Rlp.encodeBigInteger(gasPrice), Rlp.encodeBigInteger(gasLimit),
                Rlp.encodeBytes(to), Rlp.encodeBigInteger(value), Rlp.encodeBytes(data),
                Rlp.encodeBigInteger(BigInteger.valueOf(chainId)),
                Rlp.encodeBytes(new byte[0]), Rlp.encodeBytes(new byte[0]));
        byte[] hash = Keccak256.digest(signingPayload);

        EcdsaSecp256k1.Signature sig = EcdsaSecp256k1.sign(hash, privateKey);
        long v = sig.recId() + chainId * 2 + 35;

        byte[] signed = Rlp.encodeList(
                Rlp.encodeBigInteger(nonce), Rlp.encodeBigInteger(gasPrice), Rlp.encodeBigInteger(gasLimit),
                Rlp.encodeBytes(to), Rlp.encodeBigInteger(value), Rlp.encodeBytes(data),
                Rlp.encodeBigInteger(BigInteger.valueOf(v)), Rlp.encodeBigInteger(sig.r()),
                Rlp.encodeBigInteger(sig.s()));
        return "0x" + HexFormat.of().formatHex(signed);
    }

    /** ERC-20 {@code transfer(address,uint256)} calldata. */
    public static byte[] erc20TransferData(byte[] recipient20, BigInteger amount) {
        byte[] data = new byte[4 + 32 + 32];
        System.arraycopy(ERC20_TRANSFER_SELECTOR, 0, data, 0, 4);
        System.arraycopy(recipient20, 0, data, 4 + 12, 20); // address left-padded to 32 bytes
        System.arraycopy(EthKeys.to32(amount), 0, data, 4 + 32, 32);
        return data;
    }

    public static byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex.startsWith("0x") ? hex.substring(2) : hex);
    }
}
