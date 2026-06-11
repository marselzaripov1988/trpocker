package com.truholdem.service.tournament;

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

import com.truholdem.config.AppProperties;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.PyramidFederationRepository;

@DisplayName("FederationDepositPollScheduler (flag-gated, reconciles active isolated federations)")
class FederationDepositPollSchedulerTest {

    private final PyramidFederationRepository repository = mock(PyramidFederationRepository.class);
    private final FederatedPyramidService federatedService = mock(FederatedPyramidService.class);

    private FederationDepositPollScheduler scheduler(AppProperties props) {
        return new FederationDepositPollScheduler(repository, federatedService, props);
    }

    /** All four gates on by default; flip one off per test. */
    private static AppProperties props(boolean pyramid, boolean isolated, boolean poll, boolean solRpc) {
        AppProperties p = new AppProperties();
        p.getTournament().setFederatedPyramidEnabled(pyramid);
        p.getTournament().setFederatedIsolatedWalletsEnabled(isolated);
        p.getTournament().setFederatedIsolatedDepositPollEnabled(poll);
        p.getPayments().setSolRpcEnabled(solRpc);
        return p;
    }

    private static PyramidFederation federation() {
        PyramidFederation f = mock(PyramidFederation.class);
        when(f.getId()).thenReturn(UUID.randomUUID());
        return f;
    }

    @Test
    @DisplayName("reconciles every REGISTERING isolated federation when all gates are on")
    void reconcilesActiveFederations() {
        PyramidFederation a = federation();
        PyramidFederation b = federation();
        when(repository.findByStatusAndIsolatedWalletsEnabledTrue(FederationStatus.REGISTERING))
                .thenReturn(List.of(a, b));

        scheduler(props(true, true, true, true)).pollDeposits();

        verify(federatedService).reconcileDeposits(a.getId());
        verify(federatedService).reconcileDeposits(b.getId());
    }

    @Test
    @DisplayName("a failing reconcile does not abort the poll")
    void oneFailureDoesNotStopOthers() {
        PyramidFederation a = federation();
        PyramidFederation b = federation();
        when(repository.findByStatusAndIsolatedWalletsEnabledTrue(FederationStatus.REGISTERING))
                .thenReturn(List.of(a, b));
        when(federatedService.reconcileDeposits(a.getId())).thenThrow(new RuntimeException("rpc down"));

        scheduler(props(true, true, true, true)).pollDeposits();

        verify(federatedService).reconcileDeposits(a.getId());
        verify(federatedService).reconcileDeposits(b.getId()); // still attempted after the first threw
    }

    @Test
    @DisplayName("any gate off → no work")
    void gatesGuardTheWork() {
        scheduler(props(true, true, false, true)).pollDeposits();  // poll flag off
        scheduler(props(true, true, true, false)).pollDeposits();  // sol-rpc off
        scheduler(props(false, true, true, true)).pollDeposits();  // pyramid off
        scheduler(props(true, false, true, true)).pollDeposits();  // isolated off

        verify(repository, never()).findByStatusAndIsolatedWalletsEnabledTrue(any());
        verifyNoInteractions(federatedService);
    }
}
