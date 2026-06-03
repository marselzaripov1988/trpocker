package com.truholdem.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.User;
import com.truholdem.service.wallet.TournamentWalletService;

/** Real-money tournament entry: debits the caller's crypto wallet (at the tournament's fee) and registers them. */
@RestController
@RequestMapping("/tournaments")
public class TournamentWalletController {

    private final TournamentWalletService tournamentWalletService;

    public TournamentWalletController(TournamentWalletService tournamentWalletService) {
        this.tournamentWalletService = tournamentWalletService;
    }

    @PostMapping("/{tournamentId}/buy-in")
    public ResponseEntity<?> buyIn(
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal UserDetails principal) {
        User user = (User) principal;
        TournamentRegistration registration = tournamentWalletService.buyIn(
                user.getId(), tournamentId, user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of(
                "tournamentId", tournamentId,
                "playerId", registration.getPlayerId(),
                "status", registration.getStatus(),
                "currentChips", registration.getCurrentChips()));
    }
}
