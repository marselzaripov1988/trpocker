package com.truholdem.service.wallet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.btc.BtcWithdrawalCoordinator;
import com.truholdem.service.wallet.eth.EthWithdrawalCoordinator;

@DisplayName("WithdrawalReconcileScheduler (dispatch by asset, flag-gated)")
class WithdrawalReconcileSchedulerTest {

    private final WalletService walletService = mock(WalletService.class);
    private final EthWithdrawalCoordinator eth = mock(EthWithdrawalCoordinator.class);
    private final BtcWithdrawalCoordinator btc = mock(BtcWithdrawalCoordinator.class);
    private final com.truholdem.service.wallet.sol.SolWithdrawalCoordinator sol =
            mock(com.truholdem.service.wallet.sol.SolWithdrawalCoordinator.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EthWithdrawalCoordinator> ethProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BtcWithdrawalCoordinator> btcProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.truholdem.service.wallet.sol.SolWithdrawalCoordinator> solProvider =
            mock(ObjectProvider.class);

    private WithdrawalReconcileScheduler scheduler(AppProperties props) {
        when(ethProvider.getIfAvailable()).thenReturn(eth);
        when(btcProvider.getIfAvailable()).thenReturn(btc);
        when(solProvider.getIfAvailable()).thenReturn(sol);
        return new WithdrawalReconcileScheduler(walletService, props, ethProvider, btcProvider, solProvider);
    }

    private static AppProperties props(boolean enabled, boolean reconcileEnabled) {
        AppProperties p = new AppProperties();
        p.getPayments().setEnabled(enabled);
        p.getPayments().setWithdrawalReconcileEnabled(reconcileEnabled);
        return p;
    }

    private static WithdrawalRequest withdrawal(CryptoAsset asset) {
        WithdrawalRequest w = mock(WithdrawalRequest.class);
        when(w.getAsset()).thenReturn(asset);
        when(w.getId()).thenReturn(UUID.randomUUID());
        return w;
    }

    @Test
    @DisplayName("dispatches each BROADCAST withdrawal to its chain coordinator; unknown assets skipped")
    void dispatchesByAsset() {
        WithdrawalRequest ethW = withdrawal(CryptoAsset.ETH);
        WithdrawalRequest ercW = withdrawal(CryptoAsset.USDT_ERC20);
        WithdrawalRequest btcW = withdrawal(CryptoAsset.BTC);
        WithdrawalRequest solW = withdrawal(CryptoAsset.USDT_SOL);
        WithdrawalRequest tronW = withdrawal(CryptoAsset.USDT_TRC20);
        when(walletService.withdrawalsForReview(WithdrawalStatus.BROADCAST))
                .thenReturn(List.of(ethW, ercW, btcW, solW, tronW));

        scheduler(props(true, true)).reconcileBroadcast();

        verify(eth).reconcile(ethW.getId());
        verify(eth).reconcile(ercW.getId());
        verify(btc).reconcile(btcW.getId());
        verify(sol).reconcile(solW.getId());
        // TRON has no coordinator → none is called for it
        verify(eth, never()).reconcile(tronW.getId());
        verify(btc, never()).reconcile(tronW.getId());
        verify(sol, never()).reconcile(tronW.getId());
    }

    @Test
    @DisplayName("a failing reconcile does not abort the sweep")
    void oneFailureDoesNotStopOthers() {
        WithdrawalRequest a = withdrawal(CryptoAsset.ETH);
        WithdrawalRequest b = withdrawal(CryptoAsset.ETH);
        when(walletService.withdrawalsForReview(WithdrawalStatus.BROADCAST)).thenReturn(List.of(a, b));
        when(eth.reconcile(a.getId())).thenThrow(new RuntimeException("rpc down"));

        scheduler(props(true, true)).reconcileBroadcast();

        verify(eth).reconcile(a.getId());
        verify(eth).reconcile(b.getId()); // still attempted after the first threw
    }

    @Test
    @DisplayName("disabled flag → no work, no coordinator calls")
    void disabledIsInert() {
        new WithdrawalReconcileScheduler(walletService, props(true, false), ethProvider, btcProvider, solProvider)
                .reconcileBroadcast();

        verify(walletService, never()).withdrawalsForReview(any());
        verifyNoInteractions(eth, btc, sol);
    }
}
