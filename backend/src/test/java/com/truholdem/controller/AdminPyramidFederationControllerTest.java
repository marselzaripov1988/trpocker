package com.truholdem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.FederationWalletImportRequest;
import com.truholdem.dto.FederationWalletImportRequest.Entry;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.tournament.FederationRefundService;
import com.truholdem.service.wallet.sol.SolAtaProvisioner;
import com.truholdem.service.wallet.sol.SolRefundCoordinator;

@DisplayName("AdminPyramidFederationController — wallet-import tournament guard")
class AdminPyramidFederationControllerTest {

    private final FederatedPyramidService federatedService = mock(FederatedPyramidService.class);

    @SuppressWarnings("unchecked")
    private AdminPyramidFederationController controller() {
        AppProperties props = new AppProperties();
        props.getTournament().setFederatedPyramidEnabled(true);
        return new AdminPyramidFederationController(federatedService, mock(FederationRefundService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), props);
    }

    private static FederationWalletImportRequest request(String federationId) {
        return new FederationWalletImportRequest(federationId, List.of(new Entry(0, "owner", "addr")));
    }

    @Test
    @DisplayName("imports when the chunk's federationId matches the target")
    void matchingIdImports() {
        UUID id = UUID.randomUUID();
        when(federatedService.importPlayerWallets(eq(id), any())).thenReturn(1);

        var response = controller().importWallets(id, request(id.toString()));

        assertThat(response.getBody()).containsEntry("imported", 1);
        verify(federatedService).importPlayerWallets(eq(id), any());
    }

    @Test
    @DisplayName("imports when no federationId is declared (older files)")
    void nullIdImports() {
        UUID id = UUID.randomUUID();
        when(federatedService.importPlayerWallets(eq(id), any())).thenReturn(1);

        controller().importWallets(id, request(null));

        verify(federatedService).importPlayerWallets(eq(id), any());
    }

    @Test
    @DisplayName("rejects a chunk that belongs to a different tournament")
    void mismatchedIdRejected() {
        UUID id = UUID.randomUUID();
        FederationWalletImportRequest otherTournament = request(UUID.randomUUID().toString());

        assertThatThrownBy(() -> controller().importWallets(id, otherTournament))
                .isInstanceOf(IllegalArgumentException.class);

        verify(federatedService, never()).importPlayerWallets(any(), any());
    }
}
