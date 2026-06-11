package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.service.wallet.sol.SolWithdrawalCoordinator;
import com.truholdem.service.wallet.sol.SolanaRpcClient;

/**
 * With {@code app.payments.sol-rpc-enabled} off (the default), the Solana RPC client + withdrawal coordinator
 * beans are not created, so the admin {@code sol-*} endpoints' {@code solCoordinatorOrThrow()} fails fast. The
 * other chains and the rest of the wallet are unaffected.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("USDT-Solana coordinator is absent when the flag is off")
class SolWithdrawalCoordinatorDisabledTest {

    @Autowired
    private ObjectProvider<SolWithdrawalCoordinator> coordinator;
    @Autowired
    private ObjectProvider<SolanaRpcClient> rpc;

    @Test
    @DisplayName("the coordinator + RPC client beans are not present")
    void coordinatorAbsent() {
        assertThat(coordinator.getIfAvailable()).isNull();
        assertThat(rpc.getIfAvailable()).isNull();
    }
}
