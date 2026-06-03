package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the offline ETH signer against ground-truth vectors produced by an independent implementation
 * (eth-account): RLP + Keccak-256 + RFC-6979 ECDSA + EIP-155.
 */
@DisplayName("Offline Ethereum transaction signer (EIP-155)")
class EthTransactionSignerTest {

    private static byte[] hex(String h) {
        return EthTransactionSigner.hexToBytes(h);
    }

    @Test
    @DisplayName("canonical EIP-155 vector (private key 0x46…46)")
    void canonicalEip155Vector() {
        BigInteger pk = new BigInteger("46".repeat(32), 16);
        String raw = EthTransactionSigner.signLegacyTransaction(pk,
                BigInteger.valueOf(9), BigInteger.valueOf(20_000_000_000L), BigInteger.valueOf(21_000),
                hex("3535353535353535353535353535353535353535"),
                new BigInteger("1000000000000000000"), new byte[0], 1);

        assertThat(raw).isEqualTo("0xf86c098504a817c800825208943535353535353535353535353535353535353535"
                + "880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276"
                + "a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83");
    }

    @Test
    @DisplayName("native transfer, private key = 1 (recId 1 → v=38)")
    void privateKeyOneVector() {
        String raw = EthTransactionSigner.signLegacyTransaction(BigInteger.ONE,
                BigInteger.ZERO, BigInteger.valueOf(1_000_000_000L), BigInteger.valueOf(21_000),
                hex("3535353535353535353535353535353535353535"), BigInteger.valueOf(1000), new byte[0], 1);

        assertThat(raw).isEqualTo("0xf86580843b9aca0082520894353535353535353535353535353535353535353582"
                + "03e88026a081e88c530eb61c59d3bc16069906b2d346acdd08d66874e42e8099915e30cbef"
                + "a0552fb6422787a3e6a76a8acbac645f5fe7b18d5963c46a921fbff9b6d3eb0cdb");
    }

    @Test
    @DisplayName("ERC-20 transfer calldata (USDT-ERC20 style)")
    void erc20TransferVector() {
        byte[] data = EthTransactionSigner.erc20TransferData(
                hex("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"), BigInteger.valueOf(1_500_000));
        String raw = EthTransactionSigner.signLegacyTransaction(BigInteger.TWO,
                BigInteger.valueOf(7), BigInteger.valueOf(2_500_000_000L), BigInteger.valueOf(60_000),
                hex("dac17f958d2ee523a2206206994597c13d831ec7"), BigInteger.ZERO, data, 1);

        assertThat(raw).isEqualTo("0xf8a807849502f90082ea6094dac17f958d2ee523a2206206994597c13d831ec780"
                + "b844a9059cbb0000000000000000000000005aaeb6053f3e94c9b9a09f33669435e7ef1beaed"
                + "000000000000000000000000000000000000000000000000000000000016e360"
                + "25a0fc39aab7ec02de69da8c19ca110eda4d1e5d05144723914492dc45eda2fc08eb"
                + "a06347a08d080b37c50d08987ac610d6bd45fe72ea26d978b0fa377a9d7e38f1a6");
    }

    @Test
    @DisplayName("signing is deterministic (RFC 6979)")
    void deterministic() {
        BigInteger pk = new BigInteger("46".repeat(32), 16);
        byte[] hash = Keccak256.digest("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var a = EcdsaSecp256k1.sign(hash, pk);
        var b = EcdsaSecp256k1.sign(hash, pk);
        assertThat(a.r()).isEqualTo(b.r());
        assertThat(a.s()).isEqualTo(b.s());
    }
}
