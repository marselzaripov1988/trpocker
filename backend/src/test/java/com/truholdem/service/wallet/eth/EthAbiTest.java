package com.truholdem.service.wallet.eth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EthAbi (ERC-20 calldata + hex/quantity helpers)")
class EthAbiTest {

    @Test
    @DisplayName("erc20 transfer calldata: selector + left-padded address + uint256 amount")
    void erc20Transfer() {
        byte[] to = new byte[20];
        to[19] = 0x01; // 0x000...0001
        BigInteger amount = BigInteger.valueOf(0x0a);

        byte[] data = EthAbi.erc20TransferData(to, amount);
        String hex = HexFormat.of().formatHex(data);

        assertThat(data).hasSize(68); // 4 selector + 32 + 32
        assertThat(hex).startsWith("a9059cbb");

        byte[] addrWord = Arrays.copyOfRange(data, 4, 36);
        byte[] amountWord = Arrays.copyOfRange(data, 36, 68);
        assertThat(Arrays.copyOfRange(addrWord, 12, 32)).as("address right-aligned in the word").isEqualTo(to);
        assertThat(new BigInteger(1, amountWord)).isEqualTo(amount);
    }

    @Test
    @DisplayName("balanceOf calldata uses the 70a08231 selector + padded owner")
    void balanceOf() {
        byte[] owner = new byte[20];
        owner[0] = (byte) 0xab;
        byte[] data = EthAbi.balanceOfData(owner);
        assertThat(data).hasSize(36);
        assertThat(HexFormat.of().formatHex(data)).startsWith("70a08231");
        assertThat(Arrays.copyOfRange(data, 16, 36)).isEqualTo(owner);
    }

    @Test
    @DisplayName("uint256 left-pads and drops the BigInteger sign byte for high-bit values")
    void uint256Encoding() {
        assertThat(EthAbi.uint256(BigInteger.ZERO)).containsOnly((byte) 0).hasSize(32);

        BigInteger big = BigInteger.ONE.shiftLeft(255); // 0x80...00, toByteArray() has a leading 0x00 sign byte
        byte[] enc = EthAbi.uint256(big);
        assertThat(enc).hasSize(32);
        assertThat(enc[0]).isEqualTo((byte) 0x80);
        assertThat(new BigInteger(1, enc)).isEqualTo(big);

        assertThatThrownBy(() -> EthAbi.uint256(BigInteger.ONE.shiftLeft(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("hex/quantity round-trips (0x-prefix, odd-length quantities, minimal encoding)")
    void hexAndQuantity() {
        assertThat(EthAbi.toQuantity(BigInteger.ZERO)).isEqualTo("0x0");
        assertThat(EthAbi.toQuantity(BigInteger.valueOf(255))).isEqualTo("0xff");
        assertThat(EthAbi.hexToBigInteger("0x1")).isEqualTo(BigInteger.ONE);
        assertThat(EthAbi.hexToBigInteger("0x")).isEqualTo(BigInteger.ZERO);
        assertThat(EthAbi.hexToBytes("0x1")).containsExactly(0x01); // odd length tolerated
        assertThat(EthAbi.toHex(new byte[] { 0x0a, (byte) 0xbc })).isEqualTo("0x0abc");
    }

    @Test
    @DisplayName("address20 enforces a 20-byte address")
    void address20() {
        assertThat(EthAbi.address20("0x000000000000000000000000000000000000dEaD")).hasSize(20);
        assertThatThrownBy(() -> EthAbi.address20("0x1234"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
