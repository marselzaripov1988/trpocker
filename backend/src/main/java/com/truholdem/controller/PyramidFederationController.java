package com.truholdem.controller;

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
import org.springframework.web.server.ResponseStatusException;

import com.truholdem.config.AppProperties;
import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.FederationDetailResponse;
import com.truholdem.dto.FederationRegistrationResponse;
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
    @Operation(summary = "Register the authenticated player into the federation (assigned to a shard)")
    public ResponseEntity<FederationRegistrationResponse> register(@PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        assertEnabled();
        User user = (User) principal;
        PyramidFederationShard shard = federatedService.register(id, user.getId(), user.getUsername());
        FederationDetailResponse detail = federatedService.getFederationDetail(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(new FederationRegistrationResponse(
                id, user.getId(), shard.getShardIndex(), shard.getStatus(), detail.status()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Federation status (config, per-shard-status counts, champion)")
    public ResponseEntity<FederationDetailResponse> get(@PathVariable UUID id) {
        assertEnabled();
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    private void assertEnabled() {
        if (!appProperties.getTournament().isFederatedPyramidEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Federated pyramid is not enabled");
        }
    }
}
