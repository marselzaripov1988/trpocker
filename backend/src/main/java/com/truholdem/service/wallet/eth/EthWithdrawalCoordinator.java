package com.truholdem.service.wallet.eth;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.EthConfirmationDto;
import com.truholdem.dto.wallet.EthUnsignedTxDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.WalletService;

/**
 * Online half of an ETH/ERC-20 withdrawal: it assembles an <em>unsigned</em> transaction from live node state
 * (nonce, gas price, chain id), broadcasts the raw transaction the air-gapped signer produced, and reconciles
 * the receipt into the withdrawal state machine (→ CONFIRMED / FAILED). The private key never touches the
 * server — signing happens offline; this class only reads the chain and relays bytes. Active when
 * {@code app.payments.eth-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.eth-rpc-enabled", havingValue = "true")
public class EthWithdrawalCoordinator {

    private static final Logger log = LoggerFactory.getLogger(EthWithdrawalCoordinator.class);

    private final EthRpcClient rpc;
    private final WalletService walletService;
    private final AppProperties appProperties;

    public EthWithdrawalCoordinator(EthRpcClient rpc, WalletService walletService,
            AppProperties appProperties) {
        this.rpc = rpc;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** Assemble the unsigned transaction for an APPROVED withdrawal (native ETH or an ERC-20 transfer). */
    public EthUnsignedTxDto buildUnsigned(UUID withdrawalId) {
        WithdrawalRequest w = walletService.withdrawalForSigning(withdrawalId); // requires APPROVED
        AppProperties.Payments p = appProperties.getPayments();
        String from = require(p.getEthFromAddress(), "app.payments.eth-from-address");
        byte[] recipient = EthAbi.address20(w.getToAddress());

        long chainId = p.getEthChainId() > 0 ? p.getEthChainId() : rpc.chainId();
        BigInteger nonce = rpc.pendingNonce(from);
        BigInteger gasPrice = rpc.gasPrice();

        byte[] to;
        BigInteger value;
        byte[] data;
        long gasLimit;
        if (w.getAsset() == CryptoAsset.ETH) {
            to = recipient;
            value = toMinorUnits(w, CryptoAsset.ETH);
            data = new byte[0];
            gasLimit = p.getEthGasLimit();
        } else if (w.getAsset() == CryptoAsset.USDT_ERC20) {
            String contract = require(p.getErc20Contracts().get(w.getAsset().name()),
                    "app.payments.erc20-contracts." + w.getAsset().name());
            to = EthAbi.address20(contract);
            value = BigInteger.ZERO;
            data = EthAbi.erc20TransferData(recipient, toMinorUnits(w, w.getAsset()));
            gasLimit = p.getErc20GasLimit();
        } else {
            throw new IllegalArgumentException(
                    "ETH coordinator handles only ETH and USDT_ERC20, not " + w.getAsset());
        }

        return new EthUnsignedTxDto(w.getId(), w.getAsset().name(), chainId, from,
                EthAbi.toQuantity(nonce), EthAbi.toQuantity(gasPrice), gasLimit,
                EthAbi.toHex(to), EthAbi.toQuantity(value), EthAbi.toHex(data));
    }

    /** Broadcast the offline-signed raw tx for an APPROVED withdrawal and record its hash → BROADCAST. */
    public WithdrawalRequest broadcast(UUID withdrawalId, String signedRawTxHex) {
        walletService.withdrawalForSigning(withdrawalId); // assert APPROVED before touching the chain
        String txHash = rpc.sendRawTransaction(signedRawTxHex);
        log.info("Withdrawal {} broadcast on-chain (tx {})", withdrawalId, txHash);
        return walletService.recordBroadcast(withdrawalId, txHash);
    }

    /** Read-only on-chain status of a tx hash against the confirmation threshold. */
    public EthConfirmationDto confirmation(String txHash) {
        Optional<EthRpcClient.Receipt> receipt = rpc.receipt(txHash);
        if (receipt.isEmpty()) {
            return new EthConfirmationDto(txHash, false, false, 0, false);
        }
        EthRpcClient.Receipt r = receipt.get();
        long confirmations = rpc.blockNumber().subtract(r.blockNumber()).longValueExact() + 1;
        boolean confirmed = r.success()
                && confirmations >= appProperties.getPayments().getMinConfirmations();
        return new EthConfirmationDto(txHash, true, r.success(), confirmations, confirmed);
    }

    /**
     * Reconcile a BROADCAST withdrawal against its receipt: move it to CONFIRMED once the success receipt has
     * enough confirmations, or to FAILED if the transaction reverted. A no-op for any other state, an unmined
     * tx, or insufficient confirmations.
     */
    public WithdrawalRequest reconcile(UUID withdrawalId) {
        WithdrawalRequest w = walletService.getWithdrawal(withdrawalId);
        if (w.getStatus() != WithdrawalStatus.BROADCAST || w.getTxId() == null) {
            return w;
        }
        EthConfirmationDto status = confirmation(w.getTxId());
        if (!status.mined()) {
            return w;
        }
        if (!status.success()) {
            walletService.failWithdrawal(withdrawalId);
        } else if (status.confirmed()) {
            walletService.confirmWithdrawal(withdrawalId);
        } else {
            return w; // mined but not enough confirmations yet
        }
        return walletService.getWithdrawal(withdrawalId);
    }

    private BigInteger toMinorUnits(WithdrawalRequest w, CryptoAsset asset) {
        return w.getAmount().movePointRight(asset.getDecimals()).toBigIntegerExact();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the ETH withdrawal coordinator");
        }
        return value;
    }
}
