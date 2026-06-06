package com.truholdem.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.truholdem.config.AppProperties;
import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.CreateFederationRequest;
import com.truholdem.dto.FederationDetailResponse;
import com.truholdem.dto.ScheduleTournamentRequest;
import com.truholdem.model.PyramidFederation;
import com.truholdem.service.tournament.FederatedPyramidService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin control plane for federated (sharded) pyramids: create one, watch its shard/finalist progress, and
 * drive the lifecycle (promote waves, schedule + start + run the final). Gated by
 * {@code app.tournament.federated-pyramid-enabled}.
 */
@RestController
@ApiV1Config
@RequestMapping("/admin/pyramid-federations")
@Tag(name = "Admin Federated Pyramids", description = "Sharded pyramid orchestration (ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPyramidFederationController {

    private static final Logger log = LoggerFactory.getLogger(AdminPyramidFederationController.class);

    private final FederatedPyramidService federatedService;
    private final AppProperties appProperties;

    public AdminPyramidFederationController(FederatedPyramidService federatedService,
            AppProperties appProperties) {
        this.federatedService = federatedService;
        this.appProperties = appProperties;
    }

    @PostMapping
    @Operation(summary = "Create a federated pyramid (field split into shards of shardSize)")
    public ResponseEntity<FederationDetailResponse> create(@Valid @RequestBody CreateFederationRequest request) {
        assertEnabled();
        PyramidFederation fed = federatedService.createFederation(
                request.name(), request.startingPlayers(), request.shardSize(),
                request.registrationDeadline(), request.buyInAmount(), request.buyInAsset(),
                request.buyUpEnabled());
        log.info("Admin created federated pyramid {} ({} players / shard {})",
                fed.getId(), request.startingPlayers(), request.shardSize());
        return ResponseEntity.status(HttpStatus.CREATED).body(federatedService.getFederationDetail(fed.getId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Federation detail: status, per-shard-status counts, champion")
    public ResponseEntity<FederationDetailResponse> get(@PathVariable UUID id) {
        assertEnabled();
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/register-bots")
    @Operation(summary = "Bulk-register synthetic bot players (play-money load tests / simulation)")
    public ResponseEntity<FederationDetailResponse> registerBots(@PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int count) {
        assertEnabled();
        int placed = federatedService.registerBotsBatch(id, count, "Bot_");
        log.info("Admin batch-registered {} bot(s) for federation {}", placed, id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/promote")
    @Operation(summary = "Materialize READY shards into running child pyramids (up to the wave cap)")
    public ResponseEntity<FederationDetailResponse> promote(@PathVariable UUID id) {
        assertEnabled();
        int started = federatedService.promoteShards(id);
        log.info("Admin promoted {} shard(s) for federation {}", started, id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/schedule-final")
    @Operation(summary = "Schedule the final among shard winners + e-mail finalists (all shards must be done)")
    public ResponseEntity<FederationDetailResponse> scheduleFinal(@PathVariable UUID id,
            @Valid @RequestBody ScheduleTournamentRequest body) {
        assertEnabled();
        int notified = federatedService.scheduleFinal(id, body.startAt());
        log.info("Admin scheduled final for federation {} at {} ({} finalists e-mailed)",
                id, body.startAt(), notified);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/start-final")
    @Operation(summary = "Create + seed + start the final pyramid from the shard winners")
    public ResponseEntity<FederationDetailResponse> startFinal(@PathVariable UUID id) {
        assertEnabled();
        federatedService.startFinal(id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/run-final")
    @Operation(summary = "Run the final to the grand champion (bots / simulation)")
    public ResponseEntity<FederationDetailResponse> runFinal(@PathVariable UUID id) {
        assertEnabled();
        federatedService.runFinalToChampion(id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    @PostMapping("/{id}/drain-shards")
    @Operation(summary = "Run all running shards to their winners (bots / simulation / ops)")
    public ResponseEntity<FederationDetailResponse> drainShards(@PathVariable UUID id) {
        assertEnabled();
        federatedService.drainShards(id);
        return ResponseEntity.ok(federatedService.getFederationDetail(id));
    }

    private void assertEnabled() {
        if (!appProperties.getTournament().isFederatedPyramidEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Federated pyramid is not enabled");
        }
    }
}
