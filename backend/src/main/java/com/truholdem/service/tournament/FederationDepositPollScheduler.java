package com.truholdem.service.tournament;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.PyramidFederationRepository;

/**
 * Background deposit poller for isolated-custody federated pyramids: on a fixed interval it scans every
 * REGISTERING isolated federation's dedicated wallets on-chain and seats players whose buy-in has landed — so
 * registration completes without an admin clicking "reconcile". Delegates to
 * {@link FederatedPyramidService#reconcileDeposits}, whose batched balance reads keep the RPC cost at ceil(N/100)
 * per federation. Each reconcile is idempotent (an already-seated wallet is a no-op), so this is safe to run on
 * every node in a cluster. Inert unless the federated-pyramid + isolated-wallets + Solana-RPC features are on and
 * {@code app.tournament.federated-isolated-deposit-poll-enabled=true}.
 *
 * <p>With {@code app.tournament.federated-isolated-auto-release-enabled=true} it additionally auto-releases
 * no-shows in the same cycle — but only <em>after</em> reconcile, so a late-but-valid deposit is seated rather
 * than dropped. That is a separate opt-in because release deletes pending registrations.
 */
@Component
public class FederationDepositPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(FederationDepositPollScheduler.class);

    private final PyramidFederationRepository federationRepository;
    private final FederatedPyramidService federatedService;
    private final AppProperties appProperties;

    public FederationDepositPollScheduler(PyramidFederationRepository federationRepository,
            FederatedPyramidService federatedService, AppProperties appProperties) {
        this.federationRepository = federationRepository;
        this.federatedService = federatedService;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${app.tournament.federated-isolated-deposit-poll-interval-ms:30000}")
    public void pollDeposits() {
        AppProperties.Tournament t = appProperties.getTournament();
        if (!t.isFederatedPyramidEnabled() || !t.isFederatedIsolatedWalletsEnabled()
                || !t.isFederatedIsolatedDepositPollEnabled() || !appProperties.getPayments().isSolRpcEnabled()) {
            return;
        }
        boolean autoRelease = t.isFederatedIsolatedAutoReleaseEnabled();
        for (PyramidFederation fed :
                federationRepository.findByStatusAndIsolatedWalletsEnabledTrue(FederationStatus.REGISTERING)) {
            try {
                int seated = federatedService.reconcileDeposits(fed.getId());
                if (seated > 0) {
                    log.info("Deposit poll seated {} player(s) in federation {}", seated, fed.getId());
                }
                if (autoRelease) {
                    // Always after reconcile, so a late-but-valid deposit is seated rather than released.
                    int released = federatedService.releaseNoShows(fed.getId());
                    if (released > 0) {
                        log.info("Deposit poll released {} no-show(s) in federation {}", released, fed.getId());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Deposit poll for federation {} failed (will retry next interval)", fed.getId(), e);
            }
        }
    }
}
