package com.truholdem.service.wallet.sol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.service.wallet.crypto.SolKeys;

/**
 * Verifies the legacy message compilation for an SPL transfer: header, account de-dup/ordering (fee payer at
 * index 0), blockhash placement, and the instruction's compiled program/account indices + data. Asserts exact
 * byte offsets, so a regression in the wire format is caught without a live validator (slice 5 confirms it on
 * solana-test-validator).
 */
@DisplayName("SolMessage — legacy transfer message compilation")
class SolMessageTest {

    @Test
    @DisplayName("compiles an SPL transfer message with correct header, ordering, indices and data")
    void compilesTransfer() {
        byte[] owner = SolKeys.publicKeyFromSeed(seed(1)); // fee payer + transfer authority (signer)
        byte[] source = fill(0x11);
        byte[] dest = fill(0x22);
        byte[] blockhash = fill(0x33);
        long amount = 1_234_567L;

        byte[] msg = SolMessage.compile(owner, blockhash,
                List.of(SolInstructions.transfer(source, dest, owner, amount)));

        // header: 1 required signature, 0 readonly-signed, 1 readonly-unsigned (the token program).
        assertThat(msg[0]).isEqualTo((byte) 1);
        assertThat(msg[1]).isEqualTo((byte) 0);
        assertThat(msg[2]).isEqualTo((byte) 1);

        // account keys: count = 4, ordered [owner(fee payer), source, dest, tokenProgram].
        assertThat(msg[3]).isEqualTo((byte) 4);
        assertThat(slice(msg, 4, 36)).isEqualTo(owner);
        assertThat(slice(msg, 36, 68)).isEqualTo(source);
        assertThat(slice(msg, 68, 100)).isEqualTo(dest);
        assertThat(slice(msg, 100, 132)).isEqualTo(SolInstructions.TOKEN_PROGRAM_ID);

        // recent blockhash.
        assertThat(slice(msg, 132, 164)).isEqualTo(blockhash);

        // one instruction: programIdIndex=3 (token program), accounts [source=1, dest=2, owner=0].
        assertThat(msg[164]).isEqualTo((byte) 1);   // instruction count
        assertThat(msg[165]).isEqualTo((byte) 3);   // programIdIndex
        assertThat(msg[166]).isEqualTo((byte) 3);   // account-index count
        assertThat(msg[167]).isEqualTo((byte) 1);   // source
        assertThat(msg[168]).isEqualTo((byte) 2);   // dest
        assertThat(msg[169]).isEqualTo((byte) 0);   // owner (authority)

        // data: length 9 = [3] + u64 LE amount.
        assertThat(msg[170]).isEqualTo((byte) 9);
        assertThat(msg[171]).isEqualTo((byte) 3);   // SPL Transfer
        long parsed = 0;
        for (int i = 0; i < 8; i++) {
            parsed |= (msg[172 + i] & 0xffL) << (8 * i);
        }
        assertThat(parsed).isEqualTo(amount);
        assertThat(msg).hasSize(180);
    }

    @Test
    @DisplayName("fee payer is always account index 0 even if not in any instruction")
    void feePayerFirst() {
        byte[] feePayer = SolKeys.publicKeyFromSeed(seed(9));
        byte[] source = fill(0x44);
        byte[] dest = fill(0x55);
        byte[] authority = SolKeys.publicKeyFromSeed(seed(7));
        byte[] msg = SolMessage.compile(feePayer, fill(0x66),
                List.of(SolInstructions.transfer(source, dest, authority, 1)));
        assertThat(slice(msg, 4, 36)).isEqualTo(feePayer); // index 0
        assertThat(msg[0]).isEqualTo((byte) 2);            // feePayer + authority are both signers
    }

    private static byte[] slice(byte[] b, int from, int to) {
        return Arrays.copyOfRange(b, from, to);
    }

    private static byte[] fill(int v) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) v);
        return b;
    }

    private static byte[] seed(int n) {
        byte[] s = new byte[32];
        s[0] = (byte) n;
        return s;
    }
}
