package com.truholdem.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.dto.ScheduleDailyTournamentRequest;
import com.truholdem.dto.ScheduleTournamentRequest;
import com.truholdem.dto.TournamentDetailResponse;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentStatus;
import com.truholdem.model.TournamentType;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.PyramidTournamentService;
import com.truholdem.service.tournament.PyramidTournamentService.PyramidRunResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@ApiV1Config
@RequestMapping("/admin/tournaments")
@Tag(name = "Admin Tournaments", description = "Tournament moderation (ADMIN role required)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTournamentController {

    private static final Logger log = LoggerFactory.getLogger(AdminTournamentController.class);

    private final TournamentService tournamentService;
    private final PyramidTournamentService pyramidTournamentService;

    public AdminTournamentController(
            TournamentService tournamentService,
            PyramidTournamentService pyramidTournamentService) {
        this.tournamentService = tournamentService;
        this.pyramidTournamentService = pyramidTournamentService;
    }

    @PostMapping
    @Operation(summary = "Create tournament (admin)")
    public ResponseEntity<TournamentDetailResponse> createTournament(
            @Valid @RequestBody CreateTournamentRequest request) {
        log.info("Admin creating tournament: {} ({})", request.name(), request.type());
        Tournament tournament = tournamentService.createTournament(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tournamentService.getTournamentDetail(tournament.getId()));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Start tournament")
    public ResponseEntity<TournamentDetailResponse> startTournament(@PathVariable UUID id) {
        log.info("Admin starting tournament {}", id);
        tournamentService.startTournament(id);
        TournamentDetailResponse detail = tournamentService.getTournamentDetail(id);
        if (detail.status() == TournamentStatus.STARTING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(detail);
        }
        return ResponseEntity.ok(detail);
    }

    @PostMapping("/{id}/schedule")
    @Operation(summary = "Schedule a tournament's automatic start time (must still be REGISTERING)")
    public ResponseEntity<TournamentDetailResponse> scheduleStart(@PathVariable UUID id,
            @Valid @RequestBody ScheduleTournamentRequest body) {
        log.info("Admin scheduling tournament {} to auto-start at {}", id, body.startAt());
        tournamentService.scheduleStart(id, body.startAt());
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/schedule-daily")
    @Operation(summary = "Pin a tournament to a time-of-day slot (full-or-postpone); REGISTERING only")
    public ResponseEntity<TournamentDetailResponse> scheduleDaily(@PathVariable UUID id,
            @Valid @RequestBody ScheduleDailyTournamentRequest body) {
        log.info("Admin pinning tournament {} to {} (requireFull={})", id, body.timeOfDay(), body.requireFull());
        tournamentService.scheduleAtTimeOfDay(id, body.timeOfDay(), body.requireFull());
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause tournament")
    public ResponseEntity<TournamentDetailResponse> pauseTournament(@PathVariable UUID id) {
        tournamentService.pauseTournament(id);
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume paused tournament")
    public ResponseEntity<TournamentDetailResponse> resumeTournament(@PathVariable UUID id) {
        tournamentService.resumeTournament(id);
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "End tournament and crown winner")
    public ResponseEntity<TournamentDetailResponse> endTournament(@PathVariable UUID id) {
        tournamentService.endTournament(id);
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel tournament")
    public ResponseEntity<TournamentDetailResponse> cancelTournament(@PathVariable UUID id) {
        tournamentService.cancelTournament(id);
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/eliminate/{playerId}")
    @Operation(summary = "Eliminate a player")
    public ResponseEntity<TournamentDetailResponse> eliminatePlayer(
            @PathVariable UUID id,
            @PathVariable UUID playerId) {
        tournamentService.handlePlayerElimination(id, playerId);
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/register-bots")
    @Operation(summary = "Batch-register bot players (load tests / pyramid)")
    public ResponseEntity<TournamentDetailResponse> registerBots(
            @PathVariable UUID id,
            @Valid @RequestBody BatchBotRegistrationRequest request) {
        tournamentService.registerBotPlayersBatch(id, request.count(), request.namePrefix());
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/pyramid/round")
    @Operation(summary = "Advance one PYRAMID round (all tables)")
    public ResponseEntity<TournamentDetailResponse> playPyramidRound(@PathVariable UUID id) {
        assertPyramidType(id);
        pyramidTournamentService.playCurrentPyramidRound(id);
        return ResponseEntity.ok(tournamentService.getTournamentDetail(id));
    }

    @PostMapping("/{id}/pyramid/run")
    @Operation(summary = "Run PYRAMID tournament to completion (bots / simulation)")
    public ResponseEntity<PyramidRunResponse> runPyramidToCompletion(@PathVariable UUID id) {
        assertPyramidType(id);
        PyramidRunResult result = pyramidTournamentService.runToCompletion(id);
        return ResponseEntity.ok(new PyramidRunResponse(
                result.tournamentId(),
                result.championId(),
                result.roundsPlayed(),
                result.finalStatus().name()));
    }

    private void assertPyramidType(UUID id) {
        Tournament tournament = tournamentService.getTournament(id);
        if (tournament.getTournamentType() != TournamentType.PYRAMID) {
            throw new IllegalArgumentException("Tournament is not PYRAMID: " + tournament.getTournamentType());
        }
    }

    public record BatchBotRegistrationRequest(
            @Min(1) @Max(10000) int count,
            String namePrefix) {
        public BatchBotRegistrationRequest {
            if (namePrefix == null || namePrefix.isBlank()) {
                namePrefix = "Bot_";
            }
        }
    }

    public record PyramidRunResponse(
            UUID tournamentId,
            UUID championId,
            int roundsPlayed,
            String finalStatus) {
    }
}
