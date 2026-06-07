package com.truholdem.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.User;
import com.truholdem.repository.UserRepository;

/**
 * Federated pyramid REST is gated: 404 (not 500) when app.tournament.federated-pyramid-enabled is off
 * (the default). Mirrors {@link CashTableControllerDisabledIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Federated pyramid REST is gated: 404 when federated-pyramid-enabled is off (default)")
class FederatedPyramidControllerDisabledIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("the player status endpoint returns 404 when federated pyramids are disabled")
    void playerEndpointDisabled() throws Exception {
        String u = "fedoff-" + UUID.randomUUID();
        User user = userRepository.save(new User(u, u + "@example.com", "x"));
        mockMvc.perform(get("/v1/pyramid-federations/{id}", UUID.randomUUID())
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                user, null, user.getAuthorities()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the admin detail endpoint returns 404 when federated pyramids are disabled")
    void adminEndpointDisabled() throws Exception {
        mockMvc.perform(get("/v1/admin/pyramid-federations/{id}", UUID.randomUUID())
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
                .andExpect(status().isNotFound());
    }
}
