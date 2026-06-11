package com.truholdem.service.wallet.sol;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.SolAtaBatchUnsignedDto;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.service.tournament.FederationPlayerWalletService;
import com.truholdem.service.wallet.crypto.Base58;
import com.truholdem.service.wallet.sol.SolMessage.Instruction;

/**
 * Online half of dedicated-wallet ATA lifecycle for an isolated-custody federation. Two offline-signed batch
 * operations, each assembled here as an <em>unsigned</em> message, broadcast after signing, and confirmed:
 *
 * <ul>
 *   <li><b>create</b> — idempotently create a batch of dedicated wallets' USDT ATAs, rent paid by the operator
 *       ({@code app.payments.sol-from-address}, the only signer). Exchanges send a bare SPL transfer that does
 *       <em>not</em> create the recipient ATA, so the ATA must exist before a buy-in can land.</li>
 *   <li><b>close</b> — reclaim a finished wallet's ATA rent back to the operator; each {@code closeAccount} is
 *       authorized by that wallet's owner, so the batch has the operator (fee-payer) plus every wallet owner as
 *       signers (all offline; owners re-derived by derivation index).</li>
 * </ul>
 *
 * Batches are kept small ({@code app.tournament.federated-isolated-ata-batch-size}) to stay within Solana's tx
 * size + compute limits. Active when {@code app.payments.sol-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.sol-rpc-enabled", havingValue = "true")
public class SolAtaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SolAtaProvisioner.class);

    private final SolanaRpcClient rpc;
    private final FederationPlayerWalletService walletService;
    private final AppProperties appProperties;

    public SolAtaProvisioner(SolanaRpcClient rpc, FederationPlayerWalletService walletService,
            AppProperties appProperties) {
        this.rpc = rpc;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** Assemble the unsigned <b>create</b> batch for up to {@code limit} wallets whose ATA still needs creating.
     *  Only the operator signs (it pays rent). Throws if nothing needs provisioning. */
    public SolAtaBatchUnsignedDto buildCreateBatch(UUID federationId, int limit) {
        AppProperties.Payments p = appProperties.getPayments();
        String operatorAddr = require(p.getSolFromAddress(), "app.payments.sol-from-address");
        byte[] mint = Base58.decode(require(p.getSolUsdtMint(), "app.payments.sol-usdt-mint"));
        byte[] operator = Base58.decode(operatorAddr);

        List<FederationPlayerWallet> wallets = walletService.walletsNeedingAta(federationId, Math.max(1, limit));
        if (wallets.isEmpty()) {
            throw new IllegalStateException("Federation " + federationId + " has no wallets needing ATA creation");
        }

        List<Instruction> instructions = new ArrayList<>();
        List<UUID> walletIds = new ArrayList<>();
        for (FederationPlayerWallet w : wallets) {
            instructions.add(SolInstructions.createIdempotentAta(
                    operator, Base58.decode(w.getAddress()), Base58.decode(w.getOwnerPubkey()), mint));
            walletIds.add(w.getId());
        }

        String message = compile(operator, instructions);
        log.info("Assembled unsigned ATA create batch for federation {}: {} wallet(s)", federationId, wallets.size());
        return new SolAtaBatchUnsignedDto(federationId, "create", message, operatorAddr, walletIds,
                List.of(new SolAtaBatchUnsignedDto.Signer(operatorAddr, null)));
    }

    /** Assemble the unsigned <b>close</b> batch for the given wallets — reclaim each ATA's rent to the operator.
     *  Signers: the operator (fee-payer) plus each wallet owner, in that order. Throws if no wallets match. */
    public SolAtaBatchUnsignedDto buildCloseBatch(UUID federationId, List<UUID> walletIds) {
        AppProperties.Payments p = appProperties.getPayments();
        String operatorAddr = require(p.getSolFromAddress(), "app.payments.sol-from-address");
        byte[] operator = Base58.decode(operatorAddr);

        List<FederationPlayerWallet> wallets = walletService.walletsByIds(federationId, walletIds);
        if (wallets.isEmpty()) {
            throw new IllegalStateException("Federation " + federationId + " has no matching wallets to close");
        }

        List<Instruction> instructions = new ArrayList<>();
        List<UUID> covered = new ArrayList<>();
        List<SolAtaBatchUnsignedDto.Signer> signers = new ArrayList<>();
        signers.add(new SolAtaBatchUnsignedDto.Signer(operatorAddr, null)); // fee-payer, index 0
        for (FederationPlayerWallet w : wallets) {
            instructions.add(SolInstructions.closeAccount(
                    Base58.decode(w.getAddress()), operator, Base58.decode(w.getOwnerPubkey())));
            covered.add(w.getId());
            signers.add(new SolAtaBatchUnsignedDto.Signer(w.getOwnerPubkey(), w.getDerivationIndex()));
        }

        String message = compile(operator, instructions);
        log.info("Assembled unsigned ATA close batch for federation {}: {} wallet(s)", federationId, wallets.size());
        return new SolAtaBatchUnsignedDto(federationId, "close", message, operatorAddr, covered, signers);
    }

    /** Broadcast an offline-signed ATA batch; returns its signature. */
    public String broadcast(String signedTxBase64) {
        String signature = rpc.sendTransaction(signedTxBase64);
        log.info("ATA batch broadcast on-chain (signature {})", signature);
        return signature;
    }

    /** Once {@code signature} is confirmed, mark the batch's wallets ATA-provisioned. Returns the count updated
     *  (0 if not yet confirmed). */
    public int confirmCreated(UUID federationId, List<UUID> walletIds, String signature) {
        return confirmed(signature) ? walletService.markAtaProvisioned(federationId, walletIds) : 0;
    }

    /** Once {@code signature} is confirmed, mark the batch's wallets ATA-closed. Returns the count updated. */
    public int confirmClosed(UUID federationId, List<UUID> walletIds, String signature) {
        return confirmed(signature) ? walletService.markAtaClosed(federationId, walletIds) : 0;
    }

    private boolean confirmed(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        SolanaRpcClient.SignatureStatus s = rpc.getSignatureStatus(signature);
        return s.found() && !s.failed()
                && ("confirmed".equals(s.confirmationStatus()) || "finalized".equals(s.confirmationStatus()));
    }

    private String compile(byte[] feePayer, List<Instruction> instructions) {
        byte[] blockhash = Base58.decode(rpc.getLatestBlockhash().blockhash());
        return Base64.getEncoder().encodeToString(SolMessage.compile(feePayer, blockhash, instructions));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the Solana ATA provisioner");
        }
        return value;
    }
}
