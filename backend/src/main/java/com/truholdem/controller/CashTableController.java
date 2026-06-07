package com.truholdem.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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

import com.truholdem.config.AppProperties;
import com.truholdem.config.api.ApiV1Config;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.dto.CashActionRequest;
import com.truholdem.dto.CashActionResponse;
import com.truholdem.dto.CashHandResponse;
import com.truholdem.dto.CashLeaveResponse;
import com.truholdem.dto.CashSeatResponse;
import com.truholdem.dto.CashTableResponse;
import com.truholdem.dto.CashTableStateResponse;
import com.truholdem.dto.SitDownRequest;
import com.truholdem.dto.SitDownResponse;
import com.truholdem.model.CashChipScale;
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashTable;
import com.truholdem.model.Player;
import com.truholdem.model.User;
import com.truholdem.service.CashGameService;
import com.truholdem.service.CashGameService.CashActResult;
import com.truholdem.service.CashGameService.CashLeaveResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Player-facing cash (ring) table endpoints: browse the lobby, sit down (buy-in), read table state, act, and
 * stand up. Gated by {@code app.cash.enabled} (404 when off); real-money buy-ins also require
 * {@code app.payments.enabled}.
 */
@RestController
@ApiV1Config
@RequestMapping("/cash/tables")
@Tag(name = "Cash Tables", description = "Real-money ring tables: lobby, sit/leave, deal/act, state")
@SecurityRequirement(name = "bearerAuth")
public class CashTableController {

    private final CashGameService cashGameService;
    private final AppProperties appProperties;

    public CashTableController(CashGameService cashGameService, AppProperties appProperties) {
        this.cashGameService = cashGameService;
        this.appProperties = appProperties;
    }

    @GetMapping
    @Operation(summary = "List open cash tables (lobby)")
    public ResponseEntity<List<CashTableResponse>> list() {
        assertEnabled();
        List<CashTableResponse> tables = cashGameService.listActiveTables().stream()
                .map(t -> toTableResponse(t, cashGameService.seatsOf(t.getId()).size()))
                .toList();
        return ResponseEntity.ok(tables);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Cash table state: seats + the current hand (your own hole cards only)")
    public ResponseEntity<CashTableStateResponse> state(@PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        UUID userId = ((User) principal).getId();
        CashTable table = cashGameService.getTable(id);
        List<CashSeat> seats = cashGameService.seatsOf(id);
        PokerGame hand = cashGameService.peekHand(id);
        List<CashSeatResponse> seatViews = seats.stream()
                .map(s -> new CashSeatResponse(s.getSeatNumber(), s.getPlayerName(), s.getStack(), s.getStatus()))
                .toList();
        return ResponseEntity.ok(new CashTableStateResponse(
                toTableResponse(table, seats.size()), seatViews, handView(table, hand, seats, userId)));
    }

    @PostMapping("/{id}/sit")
    @Operation(summary = "Sit down with a buy-in (debits the wallet, seats you)")
    public ResponseEntity<SitDownResponse> sit(@PathVariable UUID id,
            @Valid @RequestBody SitDownRequest request, @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        User user = (User) principal;
        CashSeat seat = cashGameService.sit(user.getId(), id, user.getUsername(), request.buyIn());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SitDownResponse(
                seat.getSeatNumber(), seat.getPlayerName(), seat.getStack(), seat.getStatus()));
    }

    @PostMapping("/{id}/leave")
    @Operation(summary = "Stand up (cash out now, or at the end of the current hand)")
    public ResponseEntity<CashLeaveResponse> leave(@PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        CashLeaveResult result = cashGameService.leaveTable(((User) principal).getId(), id);
        return ResponseEntity.ok(new CashLeaveResponse(result.cashedOutNow(), result.amount()));
    }

    @PostMapping("/{id}/deal")
    @Operation(summary = "Deal the next hand (requires 2+ seated players)")
    public ResponseEntity<CashTableStateResponse> deal(@PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        cashGameService.openHand(id);
        return state(id, principal);
    }

    @PostMapping("/{id}/act")
    @Operation(summary = "Take an action on the current hand (fold/check/call/bet/raise/all-in)")
    public ResponseEntity<CashActionResponse> act(@PathVariable UUID id,
            @Valid @RequestBody CashActionRequest request, @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        CashActResult result = cashGameService.actAsUser(
                id, ((User) principal).getId(), request.action(), request.amount());
        return ResponseEntity.ok(new CashActionResponse(
                result.handComplete(), result.totalRake(), result.cashedOut()));
    }

    private static CashTableResponse toTableResponse(CashTable t, int seatedPlayers) {
        return new CashTableResponse(t.getId(), t.getName(), t.getAsset(), t.getSmallBlind(), t.getBigBlind(),
                t.getMinBuyIn(), t.getMaxBuyIn(), t.getMaxSeats(), t.getRakeBasisPoints(), t.getRakeCap(),
                seatedPlayers, t.isActive());
    }

    private static CashHandResponse handView(CashTable table, PokerGame hand, List<CashSeat> seats, UUID userId) {
        if (hand == null) {
            return CashHandResponse.idle();
        }
        CashChipScale scale = CashChipScale.forTable(table);
        String myName = seats.stream().filter(s -> s.getPlayerId().equals(userId))
                .map(CashSeat::getPlayerName).findFirst().orElse(null);
        List<String> yourCards = hand.getPlayers().stream()
                .filter(p -> p.getName().equals(myName))
                .findFirst()
                .map(p -> p.getHand().stream().map(Object::toString).toList())
                .orElse(List.of());
        Player current = hand.getCurrentPlayer();
        return new CashHandResponse(
                true,
                hand.getHandNumber(),
                hand.getPhase().name(),
                scale.toMoney(hand.getPotSize().amount()),
                current != null ? current.getName() : null,
                hand.getCommunityCards().stream().map(Object::toString).toList(),
                yourCards);
    }

    private void assertEnabled() {
        if (!appProperties.getCash().isEnabled()) {
            throw new ResourceNotFoundException("Cash tables are not enabled");
        }
    }
}
