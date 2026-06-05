package com.truholdem.controller;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.BuyPyramidSeatRequest;
import com.truholdem.dto.BuyoutTicketResponse;
import com.truholdem.dto.PyramidSeatPurchaseResponse;
import com.truholdem.model.PyramidBuyout;
import com.truholdem.model.User;
import com.truholdem.service.tournament.PyramidBuyoutService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/**
 * Player-facing buy-up pyramid endpoints: list the buyable higher-level seats ("tickets") and buy one before
 * the tournament starts. Buying charges the caller's crypto wallet at the seat price (which replaces the flat
 * buy-in). Sibling of {@link TournamentWalletController} (real-money tournament entry); same {@code /tournaments}
 * base, no API-version prefix.
 */
@RestController
@ApiV1Config
@RequestMapping("/tournaments")
public class PyramidBuyoutController {

    private static final Logger log = LoggerFactory.getLogger(PyramidBuyoutController.class);

    private final PyramidBuyoutService buyoutService;

    public PyramidBuyoutController(PyramidBuyoutService buyoutService) {
        this.buyoutService = buyoutService;
    }

    @GetMapping("/{tournamentId}/pyramid/tickets")
    @Operation(summary = "List the buyable higher-level pyramid seats (tickets) with their prices")
    public ResponseEntity<List<BuyoutTicketResponse>> tickets(@PathVariable UUID tournamentId) {
        List<BuyoutTicketResponse> tickets = buyoutService.availableTickets(tournamentId).stream()
                .map(t -> new BuyoutTicketResponse(t.level(), t.seatIndex(), t.price(), t.asset()))
                .toList();
        return ResponseEntity.ok(tickets);
    }

    @PostMapping("/{tournamentId}/pyramid/buy-seat")
    @Operation(summary = "Buy a guaranteed higher-level seat (charges the seat price, replacing the buy-in)")
    public ResponseEntity<PyramidSeatPurchaseResponse> buySeat(
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody BuyPyramidSeatRequest request) {
        User user = (User) principal;
        PyramidBuyout buyout = buyoutService.buySeat(
                tournamentId, user.getId(), request.level(), request.seatIndex());
        log.info("User {} bought pyramid seat L{}#{} in tournament {}",
                user.getId(), request.level(), request.seatIndex(), tournamentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(PyramidSeatPurchaseResponse.from(buyout));
    }
}
