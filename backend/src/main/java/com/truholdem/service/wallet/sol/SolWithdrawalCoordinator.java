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
import com.truholdem.dto.wallet.SolUnsignedTxDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.WalletService;
import com.truholdem.service.wallet.crypto.Base58;
import com.truholdem.service.wallet.crypto.SolAta;
import com.truholdem.service.wallet.sol.SolMessage.Instruction;

/**
 * Online half of a USDT-on-Solana (SPL) withdrawal: assembles an <em>unsigned</em> SPL {@code transfer} from the
 * treasury's USDT token account (ATA) to the recipient's ATA — lazily creating the recipient ATA if missing —
 * against a recent blockhash, broadcasts the offline-signed transaction, and reads its confirmation status. The
 * treasury key never touches the server: this class compiles the message + relays bytes; signing is offline
 * (ed25519). Account model → exactly one signature per withdrawal. Active when
 * {@code app.payments.sol-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.sol-rpc-enabled", havingValue = "true")
public class SolWithdrawalCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SolWithdrawalCoordinator.class);

    private final SolanaRpcClient rpc;
    private final WalletService walletService;
    private final AppProperties appProperties;

    public SolWithdrawalCoordinator(SolanaRpcClient rpc, WalletService walletService,
            AppProperties appProperties) {
        this.rpc = rpc;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** Assemble the unsigned SPL-transfer transaction for an APPROVED USDT-Solana withdrawal: send its amount to
     *  the recipient wallet (its USDT ATA, created in the same tx if it does not exist yet). */
    public SolUnsignedTxDto buildUnsigned(UUID withdrawalId) {
        WithdrawalRequest w = walletService.withdrawalForSigning(withdrawalId); // requires APPROVED
        if (w.getAsset() != CryptoAsset.USDT_SOL) {
            throw new IllegalArgumentException(
                    "Solana coordinator handles only USDT_SOL, not " + w.getAsset());
        }
        String recipientOwnerAddress = w.getToAddress();
        long amount = w.getAmount().movePointRight(w.getAsset().getDecimals()).longValueExact();
        AppProperties.Payments p = appProperties.getPayments();
        String treasuryAddr = require(p.getSolFromAddress(), "app.payments.sol-from-address");
        String mintAddr = require(p.getSolUsdtMint(), "app.payments.sol-usdt-mint");

        byte[] treasuryOwner = Base58.decode(treasuryAddr);
        byte[] mint = Base58.decode(mintAddr);
        byte[] recipientOwner = Base58.decode(recipientOwnerAddress);
        byte[] treasuryAta = SolAta.deriveAtaBytes(treasuryOwner, mint);
        byte[] recipientAta = SolAta.deriveAtaBytes(recipientOwner, mint);

        boolean recipientAtaExists = !rpc.getTokenAccountsByOwner(recipientOwnerAddress, mintAddr).isEmpty();

        List<Instruction> instructions = new ArrayList<>();
        if (!recipientAtaExists) {
            instructions.add(SolInstructions.createIdempotentAta(treasuryOwner, recipientAta, recipientOwner, mint));
        }
        instructions.add(SolInstructions.transfer(treasuryAta, recipientAta, treasuryOwner, amount));

        byte[] blockhash = Base58.decode(rpc.getLatestBlockhash().blockhash());
        byte[] message = SolMessage.compile(treasuryOwner, blockhash, instructions);

        log.info("Assembled unsigned SOL USDT transfer for withdrawal {}: {} base units → {} (createAta={})",
                withdrawalId, amount, Base58.encode(recipientAta), !recipientAtaExists);
        return new SolUnsignedTxDto(withdrawalId, Base64.getEncoder().encodeToString(message), treasuryAddr,
                Base58.encode(recipientAta), amount, !recipientAtaExists);
    }

    /** Broadcast the offline-signed (base64) tx for an APPROVED withdrawal and record its signature → BROADCAST. */
    public WithdrawalRequest broadcast(UUID withdrawalId, String signedTxBase64) {
        walletService.withdrawalForSigning(withdrawalId); // assert APPROVED before touching the chain
        String signature = rpc.sendTransaction(signedTxBase64);
        log.info("Solana withdrawal {} broadcast on-chain (signature {})", withdrawalId, signature);
        return walletService.recordBroadcast(withdrawalId, signature);
    }

    /** Reconcile a BROADCAST USDT-Solana withdrawal → CONFIRMED once its signature reaches confirmed/finalized. */
    public WithdrawalRequest reconcile(UUID withdrawalId) {
        WithdrawalRequest w = walletService.getWithdrawal(withdrawalId);
        if (w.getStatus() != WithdrawalStatus.BROADCAST || w.getTxId() == null) {
            return w;
        }
        if (isConfirmed(w.getTxId())) {
            walletService.confirmWithdrawal(withdrawalId);
        }
        return walletService.getWithdrawal(withdrawalId);
    }

    /** Raw confirmation status of a signature. */
    public SolanaRpcClient.SignatureStatus confirmation(String signature) {
        return rpc.getSignatureStatus(signature);
    }

    /** True once the signature is on-chain, succeeded, and reached at least {@code confirmed} commitment. */
    public boolean isConfirmed(String signature) {
        SolanaRpcClient.SignatureStatus s = rpc.getSignatureStatus(signature);
        return s.found() && !s.failed()
                && ("confirmed".equals(s.confirmationStatus()) || "finalized".equals(s.confirmationStatus()));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the Solana withdrawal coordinator");
        }
        return value;
    }
}
