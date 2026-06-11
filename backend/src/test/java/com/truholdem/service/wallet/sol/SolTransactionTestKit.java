package com.truholdem.service.wallet.sol;

import java.util.List;

/**
 * Test-only bridge to the package-private {@link SolTransaction} serializer, so the integration test (a
 * different package) can assemble a signed transaction the way an offline signer would.
 */
public final class SolTransactionTestKit {

    private SolTransactionTestKit() {
    }

    /** base64({@code shortvec([signature]) || message}) for a single-signer transaction. */
    public static String serialize(byte[] signature, byte[] message) {
        return SolTransaction.serializeBase64(List.of(signature), message);
    }
}
