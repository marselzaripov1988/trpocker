package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.Tournament;
import com.truholdem.model.User;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.wallet.TournamentWalletService;
import com.truholdem.service.wallet.WalletService;

/**
 * End-to-end (HTTP → controller → service → wallet → DB) for the player buy-up pyramid endpoints: list the
 * buyable "tickets" and buy a higher-level seat (which charges the wallet at the seat price).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up pyramid REST: list tickets + buy a seat (end-to-end)")
class PyramidBuyoutControllerIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TournamentService tournamentService;
    @Autowired private TournamentWalletService bridge;
    @Autowired private WalletService walletService;
    @Autowired private UserRepository userRepository;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private TournamentRegistrationRepository registrationRepository;
    @Autowired private PyramidBuyoutRepository buyoutRepository;

    private UUID tournamentId;
    private User buyer;

    @BeforeEach
    void setUp() {
        buyoutRepository.deleteAll();
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        // 1000/10 → levels 1000→100→10→1; level-2 has 100 buyable seats, price = 10 × 20 = 200.
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.pyramid("BuyUpRest " + System.currentTimeMillis(), 1000, 10, 1));
        t.setCryptoBuyInAmount(new BigDecimal("20"));
        t.setCryptoBuyInAsset(ASSET);
        t.setPyramidBuyUpEnabled(true);
        tournamentRepository.save(t);
        tournamentId = t.getId();

        String unique = "buyer-" + UUID.randomUUID();
        buyer = userRepository.save(new User(unique, unique + "@example.com", "x"));
        walletService.creditOnChainDeposit(buyer.getId(), ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("300"));
        bridge.buyIn(buyer.getId(), tournamentId, unique); // registers + charges base 20 → 280
    }

    @Test
    @DisplayName("GET tickets lists buyable higher-level seats with price + asset")
    void listsTickets() throws Exception {
        // 1 registered → level-2 seat 0 (covers [0,10)) is below the frontier, so the first buyable is seat 1.
        mockMvc.perform(get("/v1/tournaments/{id}/pyramid/tickets", tournamentId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].level").value(2))
                .andExpect(jsonPath("$[0].seatIndex").value(1))
                .andExpect(jsonPath("$[0].price").value(200))
                .andExpect(jsonPath("$[0].asset").value("USDT_TRC20"));
    }

    @Test
    @DisplayName("POST buy-seat charges the seat price and records the buy-out")
    void buysSeat() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("level", 2);
            put("seatIndex", 50);
        }});

        mockMvc.perform(post("/v1/tournaments/{id}/pyramid/buy-seat", tournamentId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tournamentId").value(tournamentId.toString()))
                .andExpect(jsonPath("$.playerId").value(buyer.getId().toString()))
                .andExpect(jsonPath("$.level").value(2))
                .andExpect(jsonPath("$.seatIndex").value(50))
                .andExpect(jsonPath("$.price").value(200))
                .andExpect(jsonPath("$.asset").value("USDT_TRC20"));

        // base 20 refunded then 200 charged: 280 + 20 − 200 = 100. Net spent vs the 300 deposit = 200 = price.
        assertThat(walletService.balance(buyer.getId(), ASSET)).isEqualByComparingTo("100");
        assertThat(buyoutRepository.findByTournamentId(tournamentId)).hasSize(1);
    }

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(buyer, null, buyer.getAuthorities());
    }
}
