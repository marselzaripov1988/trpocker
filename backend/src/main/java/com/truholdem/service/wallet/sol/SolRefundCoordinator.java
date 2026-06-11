package com.truholdem.service.wallet.sol;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.SolRefundUnsignedDto;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationRefund;
import com.truholdem.model.FederationRefundStatus;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.service.tournament.FederationRefundService;
import com.truholdem.service.wallet.crypto.Base58;
import com.truholdem.service.wallet.crypto.SolAta;
import com.truholdem.service.wallet.sol.SolMessage.Instruction;

/**
 * Online half of an isolated-custody refund: assembles an <em>unsigned</em> SPL transfer that returns USDT from
 * a dedicated player wallet to the player's address, broadcasts the offline-signed tx, and reconciles it. The
 * transfer has two signers — the operator ({@code app.payments.sol-from-address}) as fee-payer and the dedicated
 * wallet's owner as transfer authority — both signed offline (the wallet key is re-derived by its derivation
 * index). Active when {@code app.payments.sol-rpc-enabled=true}; the refund must be APPROVED first.
 */
@Component
@ConditionalOnProperty(name = "app.payments.sol-rpc-enabled", havingValue = "true")
public class SolRefundCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SolRefundCoordinator.class);

    private final SolanaRpcClient rpc;
    private final FederationRefundService refundService;
    private final FederationPlayerWalletRepository walletRepository;
    private final AppProperties appProperties;

    public SolRefundCoordinator(SolanaRpcClient rpc, FederationRefundService refundService,
            FederationPlayerWalletRepository walletRepository, AppProperties appProperties) {
        this.rpc = rpc;
        this.refundService = refundService;
        this.walletRepository = walletRepository;
        this.appProperties = appProperties;
    }

    /** Assemble the unsigned refund transfer (dedicated wallet → player), creating the destination ATA if needed. */
    public SolRefundUnsignedDto buildUnsigned(UUID refundId) {
        FederationRefund refund = refundService.forSigning(refundId); // requires APPROVED
        FederationPlayerWallet wallet = walletRepository.findById(refund.getWalletId())
                .orElseThrow(() -> new NoSuchElementException("Wallet not found: " + refund.getWalletId()));
        AppProperties.Payments p = appProperties.getPayments();
        String operatorAddr = require(p.getSolFromAddress(), "app.payments.sol-from-address");
        String mintAddr = require(p.getSolUsdtMint(), "app.payments.sol-usdt-mint");

        byte[] operator = Base58.decode(operatorAddr);
        byte[] mint = Base58.decode(mintAddr);
        byte[] sourceAta = Base58.decode(wallet.getAddress());
        byte[] authority = Base58.decode(wallet.getOwnerPubkey());
        byte[] destOwner = Base58.decode(refund.getToAddress());
        byte[] destAta = SolAta.deriveAtaBytes(destOwner, mint);
        long amount = refund.getNetAmount().movePointRight(refund.getAsset().getDecimals()).longValueExact();

        boolean destExists = !rpc.getTokenAccountsByOwner(refund.getToAddress(), mintAddr).isEmpty();
        List<Instruction> instructions = new ArrayList<>();
        if (!destExists) {
            instructions.add(SolInstructions.createIdempotentAta(operator, destAta, destOwner, mint)); // operator pays
        }
        instructions.add(SolInstructions.transfer(sourceAta, destAta, authority, amount));

        byte[] blockhash = Base58.decode(rpc.getLatestBlockhash().blockhash());
        byte[] message = SolMessage.compile(operator, blockhash, instructions); // fee payer = operator

        log.info("Assembled unsigned refund {}: {} base units {} → {} (createDest={})",
                refundId, amount, wallet.getAddress(), Base58.encode(destAta), !destExists);
        return new SolRefundUnsignedDto(refundId, Base64.getEncoder().encodeToString(message), operatorAddr,
                wallet.getOwnerPubkey(), wallet.getDerivationIndex(), Base58.encode(destAta), amount, !destExists);
    }

    /** Broadcast the offline-signed refund tx and record its signature → BROADCAST. */
    public FederationRefund broadcast(UUID refundId, String signedTxBase64) {
        refundService.forSigning(refundId); // assert APPROVED before touching the chain
        String signature = rpc.sendTransaction(signedTxBase64);
        log.info("Refund {} broadcast on-chain (signature {})", refundId, signature);
        return refundService.recordBroadcast(refundId, signature);
    }

    /** Reconcile a BROADCAST refund → CONFIRMED once its signature reaches confirmed/finalized. */
    public FederationRefund reconcile(UUID refundId) {
        FederationRefund refund = refundService.get(refundId);
        if (refund.getStatus() != FederationRefundStatus.BROADCAST || refund.getTxId() == null) {
            return refund;
        }
        SolanaRpcClient.SignatureStatus s = rpc.getSignatureStatus(refund.getTxId());
        boolean confirmed = s.found() && !s.failed()
                && ("confirmed".equals(s.confirmationStatus()) || "finalized".equals(s.confirmationStatus()));
        if (confirmed) {
            return refundService.confirm(refundId);
        }
        return refund;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the Solana refund coordinator");
        }
        return value;
    }
}
