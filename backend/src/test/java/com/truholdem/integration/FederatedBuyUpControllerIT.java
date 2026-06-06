package com.truholdem.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.User;
import com.truholdem.repository.PyramidFederationFinalBuyoutRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.wallet.WalletService;

/**
 * Buy-up federated pyramid REST (slice 4a): an admin creates a buy-up federation and a funded player lists the
 * buyable final seats and buys one over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.tournament.federated-pyramid-enabled=true",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up federated pyramid REST: admin create + player buys a final seat")
class FederatedBuyUpControllerIT {

    private static final String ADMIN = "/v1/admin/pyramid-federations";
    private static final String PLAYER = "/v1/pyramid-federations";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WalletService walletService;
    @Autowired private UserRepository userRepository;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationFinalBuyoutRepository finalBuyoutRepository;

    private User player;

    @BeforeEach
    void setUp() {
        finalBuyoutRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        String u = "buyup-" + UUID.randomUUID();
        player = userRepository.save(new User(u, u + "@example.com", "x"));
        walletService.creditOnChainDeposit(player.getId(), CryptoAsset.USDT_TRC20,
                "tx-" + UUID.randomUUID(), new BigDecimal("300"));
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("admin creates a buy-up federation; the player lists + buys a final seat (price 80), then it's gone")
    void createListBuyFinalSeat() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("name", "BuyUp Federation");
            put("startingPlayers", 8);
            put("shardSize", 4);
            put("buyInAmount", 20);
            put("buyInAsset", "USDT_TRC20");
            put("buyUpEnabled", true);
        }});
        String created = mockMvc.perform(post(ADMIN).with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shardCount").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        // Final seats: both (empty) shards are buyable at 4 × 20 = 80.
        mockMvc.perform(get(PLAYER + "/{id}/final-seats", id).with(authentication(playerAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(80))
                .andExpect(jsonPath("$[0].asset").value("USDT_TRC20"));

        // Buy the final seat that closes shard 1.
        mockMvc.perform(post(PLAYER + "/{id}/final-seats/{i}/buy", id, 1).with(authentication(playerAuth())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shardIndex").value(1))
                .andExpect(jsonPath("$.price").value(80))
                .andExpect(jsonPath("$.playerId").value(player.getId().toString()));

        // Shard 1 is no longer offered.
        mockMvc.perform(get(PLAYER + "/{id}/final-seats", id).with(authentication(playerAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.shardIndex == 1)]").isEmpty());
    }

    private UsernamePasswordAuthenticationToken playerAuth() {
        return new UsernamePasswordAuthenticationToken(player, null, player.getAuthorities());
    }
}
