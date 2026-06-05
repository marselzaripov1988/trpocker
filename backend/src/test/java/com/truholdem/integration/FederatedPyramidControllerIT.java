package com.truholdem.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.User;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.repository.UserRepository;

/**
 * Federated pyramid REST (slice 6): admin create + detail + lifecycle and player register, end-to-end over
 * HTTP. seats=2 keeps an 8-player / 4-shard federation a valid plan; the flag is enabled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.tournament.federated-pyramid-enabled=true",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.federated-max-concurrent-shards=2"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Federated pyramid REST: admin create/detail + player register")
class FederatedPyramidControllerIT {

    private static final String ADMIN = "/v1/admin/pyramid-federations";
    private static final String PLAYER = "/v1/pyramid-federations";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PyramidFederationRegistrationRepository registrationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationRepository federationRepository;

    private User player;

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        String u = "fedplayer-" + UUID.randomUUID();
        player = userRepository.save(new User(u, u + "@example.com", "x"));
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("admin creates a federation, a player registers into shard 0, detail reflects it")
    void createRegisterDetail() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("name", "Million Pyramid");
            put("startingPlayers", 8);
            put("shardSize", 2);
        }});

        String created = mockMvc.perform(post(ADMIN).with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shardCount").value(4))
                .andExpect(jsonPath("$.status").value("REGISTERING"))
                .andExpect(jsonPath("$.shardsRegistering").value(1))
                .andExpect(jsonPath("$.shardsPending").value(3))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post(PLAYER + "/{id}/register", id).with(authentication(
                        new UsernamePasswordAuthenticationToken(player, null, player.getAuthorities()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shardIndex").value(0))
                .andExpect(jsonPath("$.playerId").value(player.getId().toString()));

        mockMvc.perform(get(ADMIN + "/{id}", id).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredPlayers").value(1));
    }

    @Test
    @DisplayName("scheduling the final before the shards are done is rejected (409)")
    void scheduleFinalTooEarly() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("name", "Million Pyramid");
            put("startingPlayers", 8);
            put("shardSize", 2);
        }});
        String created = mockMvc.perform(post(ADMIN).with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        String when = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("startAt", "2099-01-01T00:00:00Z");
        }});
        mockMvc.perform(post(ADMIN + "/{id}/schedule-final", id).with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content(when))
                .andExpect(status().isConflict());
    }
}
