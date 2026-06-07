package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.User;
import com.truholdem.repository.CashRakeEntryRepository;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.CashRakeService;
import com.truholdem.service.wallet.WalletService;

/**
 * End-to-end money round-trip over HTTP: deposit → sit (buy-in) → deal → play a contested hand to showdown →
 * leave (cash out). Asserts the money is conserved — the total deposited equals the sum of the players' final
 * wallet balances plus the house rake — i.e. nothing is created or lost across the engine + wallet boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.cash.enabled=true", "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash games end-to-end: buy-in → play → cash-out conserves money (minus rake)")
class CashGameEndToEndIT {

    private static final String ADMIN = "/v1/admin/cash/tables";
    private static final String CASH = "/v1/cash/tables";
    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletService walletService;
    @Autowired private CashRakeService rakeService;
    @Autowired private CashTableRepository cashTableRepository;
    @Autowired private CashSeatRepository cashSeatRepository;
    @Autowired private CashRakeEntryRepository rakeRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        rakeRepository.deleteAll();
        cashSeatRepository.deleteAll();
        cashTableRepository.deleteAll();
        alice = depositedUser();
        bob = depositedUser();
    }

    private User depositedUser() {
        String u = "e2e-" + UUID.randomUUID();
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
    @DisplayName("deposit 100 total → play a raked showdown → cash out: wallets + house rake = 100")
    void moneyRoundTrip() throws Exception {
        // Admin creates a 0.05/0.10 table, 5% rake capped at 1.00.
        String createBody = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("name", "E2E USDT");
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
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID tableId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        // Both buy in 10.00 (wallet 50 → 40 each).
        sit(alice, tableId);
        sit(bob, tableId);
        assertThat(walletBalance(alice)).isEqualByComparingTo("40.00");
        assertThat(walletBalance(bob)).isEqualByComparingTo("40.00");

        // Deal and play the hand to showdown over HTTP (check/call only → contested).
        mockMvc.perform(post(CASH + "/{id}/deal", tableId).with(as(alice))).andExpect(status().isOk());
        driveToShowdown(tableId);

        // Hand settled, rake taken.
        assertThat(rakeService.houseRevenue(tableId)).isEqualByComparingTo("0.01");

        // Both stand up and cash out their remaining stacks.
        mockMvc.perform(post(CASH + "/{id}/leave", tableId).with(as(alice)))
                .andExpect(status().isOk());
        mockMvc.perform(post(CASH + "/{id}/leave", tableId).with(as(bob)))
                .andExpect(status().isOk());

        // Money is conserved: total deposited (100) = both wallets + the house rake (0.01).
        BigDecimal aliceFinal = walletBalance(alice);
        BigDecimal bobFinal = walletBalance(bob);
        assertThat(aliceFinal.add(bobFinal)).isEqualByComparingTo("99.99");
        assertThat(aliceFinal.add(bobFinal).add(new BigDecimal("0.01"))).isEqualByComparingTo("100.00");
    }

    private void sit(User user, UUID tableId) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("buyIn", "10.00");
        }});
        mockMvc.perform(post(CASH + "/{id}/sit", tableId).with(as(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Drive the live hand to showdown by checking (falling back to calling) for whoever is to act. */
    private void driveToShowdown(UUID tableId) throws Exception {
        for (int i = 0; i < 100; i++) {
            String stateJson = mockMvc.perform(get(CASH + "/{id}", tableId).with(as(alice)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode hand = objectMapper.readTree(stateJson).get("hand");
            if (!hand.get("inProgress").asBoolean()) {
                return;
            }
            String actorName = hand.get("currentActorName").asText();
            User actor = actorName.equals(alice.getUsername()) ? alice : bob;
            checkOrCall(actor, tableId);
        }
        throw new AssertionError("hand did not finish within 100 actions");
    }

    private void checkOrCall(User actor, UUID tableId) throws Exception {
        int status = mockMvc.perform(post(CASH + "/{id}/act", tableId).with(as(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CHECK\"}"))
                .andReturn().getResponse().getStatus();
        if (status != 200) {
            mockMvc.perform(post(CASH + "/{id}/act", tableId).with(as(actor))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CALL\"}"))
                    .andExpect(status().isOk());
        }
    }

    private BigDecimal walletBalance(User u) {
        return walletService.balance(u.getId(), ASSET);
    }
}
