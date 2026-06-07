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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.User;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.cash.enabled=true", "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash table REST: create + lobby + sit + deal + act + leave")
class CashTableControllerIT {

    private static final String ADMIN = "/v1/admin/cash/tables";
    private static final String CASH = "/v1/cash/tables";
    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletService walletService;
    @Autowired private CashTableRepository cashTableRepository;
    @Autowired private CashSeatRepository cashSeatRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        cashSeatRepository.deleteAll();
        cashTableRepository.deleteAll();
        alice = fundedUser();
        bob = fundedUser();
    }

    private User fundedUser() {
        String u = "cashp-" + UUID.randomUUID();
        User user = userRepository.save(new User(u, u + "@example.com", "x"));
        walletService.creditOnChainDeposit(user.getId(), ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("50"));
        return user;
    }

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    @DisplayName("full flow: admin creates → lobby lists → two players sit → deal → fold → leave")
    void fullFlow() throws Exception {
        String createBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("name", "Micro USDT");
            put("asset", "USDT_TRC20");
            put("smallBlind", "0.05");
            put("bigBlind", "0.10");
            put("minBuyIn", "2.00");
            put("maxBuyIn", "20.00");
            put("maxSeats", 6);
            put("rakeBasisPoints", 500);
            put("rakeCap", "1.00");
        }});
        String created = mockMvc.perform(post(ADMIN).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bigBlind").value(0.10))
                .andExpect(jsonPath("$.maxSeats").value(6))
                .andReturn().getResponse().getContentAsString();
        UUID tableId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        // lobby lists the open table
        mockMvc.perform(get(CASH).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tableId.toString()))
                .andExpect(jsonPath("$[0].seatedPlayers").value(0));

        // both players sit down with a buy-in
        sit(alice, tableId).andExpect(status().isCreated()).andExpect(jsonPath("$.stack").value(10.00));
        sit(bob, tableId).andExpect(status().isCreated()).andExpect(jsonPath("$.seatNumber").value(1));

        // state shows two seats and an idle hand
        mockMvc.perform(get(CASH + "/{id}", tableId).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.hand.inProgress").value(false));

        // deal a hand
        mockMvc.perform(post(CASH + "/{id}/deal", tableId).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hand.inProgress").value(true))
                .andExpect(jsonPath("$.hand.phase").value("PRE_FLOP"));

        // the current actor sees their own two hole cards; resolve who is to act and fold them
        String state = mockMvc.perform(get(CASH + "/{id}", tableId).with(as(alice)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String actorName = objectMapper.readTree(state).at("/hand/currentActorName").asText();
        User actor = actorName.equals(alice.getUsername()) ? alice : bob;

        String actBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("action", "FOLD");
        }});
        mockMvc.perform(post(CASH + "/{id}/act", tableId).with(as(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(actBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handComplete").value(true));

        // table is freed; the other player can leave and cash out immediately
        User other = actor == alice ? bob : alice;
        mockMvc.perform(post(CASH + "/{id}/leave", tableId).with(as(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashedOutNow").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions sit(User user, UUID tableId) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("buyIn", "10.00");
        }});
        return mockMvc.perform(post(CASH + "/{id}/sit", tableId).with(as(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
