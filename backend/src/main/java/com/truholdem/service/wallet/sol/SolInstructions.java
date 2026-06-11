package com.truholdem.service.wallet.sol;

import java.util.List;

import com.truholdem.service.wallet.crypto.Base58;
import com.truholdem.service.wallet.crypto.SolAta;
import com.truholdem.service.wallet.sol.SolMessage.AccountMeta;
import com.truholdem.service.wallet.sol.SolMessage.Instruction;

/**
 * Builders for the two SPL instructions the withdrawal coordinator needs: a token {@code Transfer} (move USDT
 * between token accounts, authorized by the source owner) and an Associated-Token-Account {@code CreateIdempotent}
 * (lazily create the recipient's ATA, a no-op if it already exists).
 */
final class SolInstructions {

    /** System program (all-zero pubkey). */
    static final byte[] SYSTEM_PROGRAM_ID = Base58.decode("11111111111111111111111111111111");
    static final byte[] TOKEN_PROGRAM_ID = Base58.decode(SolAta.TOKEN_PROGRAM_ID);
    static final byte[] ASSOCIATED_TOKEN_PROGRAM_ID = Base58.decode(SolAta.ASSOCIATED_TOKEN_PROGRAM_ID);

    private static final byte SPL_TRANSFER = 3;          // SPL Token: Transfer
    private static final byte SPL_CLOSE_ACCOUNT = 9;     // SPL Token: CloseAccount
    private static final byte ATA_CREATE_IDEMPOTENT = 1; // Associated Token Account: CreateIdempotent

    private SolInstructions() {
    }

    /** SPL Token {@code Transfer}: move {@code amount} base units from {@code source} to {@code destination},
     *  authorized by {@code owner} (the source token account's owner, a signer). */
    static Instruction transfer(byte[] source, byte[] destination, byte[] owner, long amount) {
        byte[] data = new byte[9];
        data[0] = SPL_TRANSFER;
        writeU64LE(data, 1, amount);
        return new Instruction(TOKEN_PROGRAM_ID, List.of(
                new AccountMeta(source, false, true),
                new AccountMeta(destination, false, true),
                new AccountMeta(owner, true, false)), data);
    }

    /** Associated Token Account {@code CreateIdempotent}: create {@code ata} (= ATA(owner, mint)) funded by
     *  {@code payer}; a no-op if it already exists. */
    static Instruction createIdempotentAta(byte[] payer, byte[] ata, byte[] owner, byte[] mint) {
        return new Instruction(ASSOCIATED_TOKEN_PROGRAM_ID, List.of(
                new AccountMeta(payer, true, true),
                new AccountMeta(ata, false, true),
                new AccountMeta(owner, false, false),
                new AccountMeta(mint, false, false),
                new AccountMeta(SYSTEM_PROGRAM_ID, false, false),
                new AccountMeta(TOKEN_PROGRAM_ID, false, false)),
                new byte[] { ATA_CREATE_IDEMPOTENT });
    }

    /** SPL Token {@code CloseAccount}: close the (empty) token account {@code account}, sending its reclaimed
     *  rent lamports to {@code destination}, authorized by {@code owner} (a signer). The account must hold zero
     *  tokens — used to recover the ATA rent deposit once a dedicated wallet is finished. */
    static Instruction closeAccount(byte[] account, byte[] destination, byte[] owner) {
        return new Instruction(TOKEN_PROGRAM_ID, List.of(
                new AccountMeta(account, false, true),
                new AccountMeta(destination, false, true),
                new AccountMeta(owner, true, false)),
                new byte[] { SPL_CLOSE_ACCOUNT });
    }

    private static void writeU64LE(byte[] dst, int off, long value) {
        for (int i = 0; i < 8; i++) {
            dst[off + i] = (byte) (value >>> (8 * i));
        }
    }
}
