package com.truholdem.service.wallet.btc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.BtcConfirmationDto;
import com.truholdem.dto.wallet.BtcTxInputDto;
import com.truholdem.dto.wallet.BtcTxOutputDto;
import com.truholdem.dto.wallet.BtcUnsignedTxDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.WalletService;

/**
 * Online half of a BTC (P2WPKH) withdrawal: selects UTXOs + computes the fee/change from a Bitcoin Core node,
 * assembles an <em>unsigned</em> transaction for the air-gapped signer, broadcasts the signed raw tx, and
 * reconciles confirmations into the withdrawal state machine (→ CONFIRMED). The private key never touches the
 * server — signing happens offline; this class only reads the chain and relays bytes. Active when
 * {@code app.payments.btc-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.btc-rpc-enabled", havingValue = "true")
public class BtcWithdrawalCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BtcWithdrawalCoordinator.class);

    // Rough P2WPKH vsize model (vbytes): tx overhead + per-input + per-output.
    private static final long VSIZE_OVERHEAD = 11;
    private static final long VSIZE_PER_INPUT = 68;
    private static final long VSIZE_PER_OUTPUT = 31;
    private static final long DUST_SAT = 294; // P2WPKH dust threshold

    private final BtcRpcClient rpc;
    private final WalletService walletService;
    private final AppProperties appProperties;

    public BtcWithdrawalCoordinator(BtcRpcClient rpc, WalletService walletService,
            AppProperties appProperties) {
        this.rpc = rpc;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** Assemble the unsigned P2WPKH transaction for an APPROVED BTC withdrawal (UTXO selection + fee/change). */
    public BtcUnsignedTxDto buildUnsigned(java.util.UUID withdrawalId) {
        WithdrawalRequest w = walletService.withdrawalForSigning(withdrawalId); // requires APPROVED
        if (w.getAsset() != CryptoAsset.BTC) {
            throw new IllegalArgumentException("BTC coordinator handles only BTC, not " + w.getAsset());
        }
        AppProperties.Payments p = appProperties.getPayments();
        String network = p.getBtcNetwork();
        String from = require(p.getBtcFromAddress(), "app.payments.btc-from-address");
        long feeRate = p.getBtcFeeRateSatPerVbyte();
        long amountSat = w.getAmount().movePointRight(8).longValueExact();

        byte[] recipientScript = BtcScript.p2wpkhScriptPubKey(w.getToAddress(), network);
        byte[] changeScript = BtcScript.p2wpkhScriptPubKey(from, network);

        List<BtcRpcClient.Utxo> spendable = rpc.listUnspent(from).stream()
                .filter(u -> u.confirmations() >= p.getBtcMinUtxoConfirmations())
                .sorted(Comparator.comparingLong(BtcRpcClient.Utxo::valueSat).reversed())
                .toList();

        List<BtcRpcClient.Utxo> chosen = new ArrayList<>();
        long inSum = 0;
        long fee = 0;
        for (BtcRpcClient.Utxo u : spendable) {
            chosen.add(u);
            inSum += u.valueSat();
            fee = feeFor(chosen.size(), 2, feeRate); // assume a change output while selecting
            if (inSum >= amountSat + fee) {
                break;
            }
        }
        if (chosen.isEmpty() || inSum < amountSat + fee) {
            throw new IllegalStateException("Insufficient on-chain BTC at " + from
                    + " to cover " + amountSat + " sat + fee (available " + inSum + " sat)");
        }

        long change = inSum - amountSat - fee;
        List<BtcTxOutputDto> outputs = new ArrayList<>();
        outputs.add(new BtcTxOutputDto(amountSat, BtcScript.toHex(recipientScript), "recipient"));
        if (change >= DUST_SAT) {
            outputs.add(new BtcTxOutputDto(change, BtcScript.toHex(changeScript), "change"));
        } else {
            fee += change; // dust change → give it to the miner (drop the change output)
        }

        List<BtcTxInputDto> inputs = chosen.stream()
                .map(u -> new BtcTxInputDto(u.txid(), u.vout(), u.valueSat(), u.scriptPubKeyHex()))
                .toList();

        log.info("Assembled unsigned BTC tx for withdrawal {}: {} input(s), {} output(s), fee {} sat",
                withdrawalId, inputs.size(), outputs.size(), fee);
        return new BtcUnsignedTxDto(w.getId(), network, 2, 0L, fee, inputs, outputs);
    }

    /** Broadcast the offline-signed raw tx for an APPROVED withdrawal and record its txid → BROADCAST. */
    public WithdrawalRequest broadcast(java.util.UUID withdrawalId, String signedRawTxHex) {
        walletService.withdrawalForSigning(withdrawalId); // assert APPROVED before touching the chain
        String txid = rpc.sendRawTransaction(signedRawTxHex);
        log.info("Withdrawal {} broadcast on-chain (tx {})", withdrawalId, txid);
        return walletService.recordBroadcast(withdrawalId, txid);
    }

    /** Read-only confirmation status of a txid against the threshold. */
    public BtcConfirmationDto confirmation(String txid) {
        long confirmations = rpc.confirmations(txid);
        boolean confirmed = confirmations >= appProperties.getPayments().getMinConfirmations();
        return new BtcConfirmationDto(txid, confirmations, confirmed);
    }

    /** Reconcile a BROADCAST withdrawal → CONFIRMED once its tx has enough confirmations. */
    public WithdrawalRequest reconcile(java.util.UUID withdrawalId) {
        WithdrawalRequest w = walletService.getWithdrawal(withdrawalId);
        if (w.getStatus() != WithdrawalStatus.BROADCAST || w.getTxId() == null) {
            return w;
        }
        if (confirmation(w.getTxId()).confirmed()) {
            walletService.confirmWithdrawal(withdrawalId);
        }
        return walletService.getWithdrawal(withdrawalId);
    }

    private static long feeFor(int nIn, int nOut, long feeRate) {
        return (VSIZE_OVERHEAD + (long) nIn * VSIZE_PER_INPUT + (long) nOut * VSIZE_PER_OUTPUT) * feeRate;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the BTC withdrawal coordinator");
        }
        return value;
    }
}
