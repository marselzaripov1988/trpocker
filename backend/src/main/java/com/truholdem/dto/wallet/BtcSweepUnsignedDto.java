package com.truholdem.dto.wallet;

import java.util.List;
import java.util.UUID;

/**
 * The unsigned BTC sweep transaction handed to the air-gapped signer: N deposit-address inputs consolidated
 * into a <b>single</b> output to the treasury ({@code toAddress}, {@code outValueSat} = Σ inputs − {@code feeSat}).
 * The signer signs each input with the key for its {@link BtcSweepInputDto#derivationIndex()} and serializes the
 * witness tx; the resulting raw hex goes back to {@code broadcast(sweepBatchId, …)}.
 */
public record BtcSweepUnsignedDto(
        UUID sweepBatchId,
        String network,
        int version,
        long locktime,
        long feeSat,
        String toAddress,
        long outValueSat,
        List<BtcSweepInputDto> inputs) {
}
