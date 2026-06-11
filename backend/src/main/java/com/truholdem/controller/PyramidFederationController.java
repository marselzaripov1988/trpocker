package com.truholdem.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.AppProperties;
import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.FederationDetailResponse;
import com.truholdem.dto.FederationRegistrationResponse;
import com.truholdem.dto.FinalSeatPurchaseResponse;
import com.truholdem.dto.FinalSeatResponse;
import com.truholdem.exception.ResourceNotFoundException;
import com.truholdem.model.PyramidFederationFinalBuyout;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.model.User;
import com.truholdem.service.tournament.FederatedPyramidService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Player-facing federated pyramid endpoints: register (the caller is assigned to a shard by fill order) and
 * read the federation's status. Gated by {@code app.tournament.federated-pyramid-enabled}.
 */
@RestController
@ApiV1Config
@RequestMapping("/pyramid-federations")
@Tag(name = "Federated Pyramids", description = "Sharded pyramid registration + status")
@SecurityRequirement(name = "bearerAuth")
public class PyramidFederationController {

    private final FederatedPyramidService federatedService;
    private final AppProperties appProperties;

    public PyramidFederationController(FederatedPyramidService federatedService, AppProperties appProperties) {
        this.federatedService = federatedService;
        this.appProperties = appProperties;
    }

    @PostMapping("/{id}/register")
    @Operation(summary = "Register the authenticated player (assigned to a shard, or a dedicated wallet if isolated)")
    public ResponseEntity<FederationRegistrationResponse> register(@PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        User user = (User) principal;
        FederationDetailResponse detail = federatedService.getFederationDetail(id);
        if (detail.isolatedWalletsEnabled()) {
            // Isolated custody: hand back the dedicated wallet to pay the buy-in into; not yet seated.
            var wallet = federatedService.registerIsolated(id, user.getId(), user.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(new FederationRegistrationResponse(
                    id, user.getId(), -1, null, detail.status(), wallet.getAddress()));
        }
        PyramidFederationShard shard = federatedService.register(id, user.getId(), user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(new FederationRegistrationResponse(
                id, user.getId(), shard.getShardIndex(), shard.getStatus(), detail.status(), null));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Federation status (config, per-shard-status counts, champion)")
    public ResponseEntity<FederationDetailResponse> get(@PathVariable UUID id) {
        assertEnabled();
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @GetMapping("/{id}/final-seats")
    @Operation(summary = "Buy-up: the buyable final seats (close an empty shard to become a finalist) + prices")
    public ResponseEntity<List<FinalSeatResponse>> finalSeats(@PathVariable UUID id) {
        assertEnabled();
        List<FinalSeatResponse> seats = federatedService.availableFinalSeats(id).stream()
                .map(t -> new FinalSeatResponse(t.shardIndex(), t.price(), t.asset()))
                .toList();
        return ResponseEntity.ok(seats);
    }

    @PostMapping("/{id}/final-seats/{shardIndex}/buy")
    @Operation(summary = "Buy-up: buy a guaranteed final seat (closes shard {shardIndex}; charges the wallet)")
    public ResponseEntity<FinalSeatPurchaseResponse> buyFinalSeat(@PathVariable UUID id,
            @PathVariable int shardIndex, @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        User user = (User) principal;
        PyramidFederationFinalBuyout buyout = federatedService.buyFinalSeat(id, user.getId(), shardIndex);
        return ResponseEntity.status(HttpStatus.CREATED).body(new FinalSeatPurchaseResponse(
                id, user.getId(), buyout.getShardIndex(), buyout.getPriceAmount(), buyout.getAsset()));
    }

    private void assertEnabled() {
        if (!appProperties.getTournament().isFederatedPyramidEnabled()) {
            throw new ResourceNotFoundException("Federated pyramid is not enabled");
        }
    }
}
