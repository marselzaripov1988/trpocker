package com.truholdem.dto.wallet;

import java.util.UUID;

/**
 * An unsigned Ethereum transaction assembled from live node state, for the air-gapped offline signer. All
 * quantities are 0x-prefixed hex. The signer reconstructs and EIP-155-signs the legacy transaction from these
 * fields, then the signed raw tx is broadcast back via the coordinator. No private key is involved here.
 */
public record EthUnsignedTxDto(
        UUID withdrawalId,
        String asset,
        long chainId,
        String from,
        String nonce,
        String gasPrice,
        long gasLimit,
        String to,
        String value,
        String data) {
}
