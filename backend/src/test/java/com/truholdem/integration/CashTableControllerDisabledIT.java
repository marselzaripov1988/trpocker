package com.truholdem.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.User;
import com.truholdem.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash table REST is gated: 404 when app.cash.enabled is off (default)")
class CashTableControllerDisabledIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("the lobby returns 404 when cash tables are disabled")
    void lobbyDisabled() throws Exception {
        String u = "cashoff-" + UUID.randomUUID();
        User user = userRepository.save(new User(u, u + "@example.com", "x"));
        mockMvc.perform(get("/v1/cash/tables")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                user, null, user.getAuthorities()))))
                .andExpect(status().isNotFound());
    }
}
