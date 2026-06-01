package com.truholdem.controller;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.ErrorResponse;
import com.truholdem.dto.PlayerStatisticsResponse;
import com.truholdem.service.PlayerStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/stats")
@Tag(name = "Statistics", description = "Player statistics, leaderboards, and performance metrics")
public class StatisticsController {

    private final PlayerStatisticsService statsService;

    public StatisticsController(PlayerStatisticsService statsService) {
        this.statsService = statsService;
    }



    @GetMapping("/player/{playerName}")
    @Operation(
        summary = "Get player statistics by name",
        description = "Retrieve comprehensive statistics for a player by their username"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Statistics retrieved successfully",
            content = @Content(schema = @Schema(implementation = PlayerStatisticsResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Player not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public ResponseEntity<PlayerStatisticsResponse> getPlayerStats(
            @Parameter(description = "Player username") @PathVariable String playerName) {
        return statsService.getStatsByName(playerName)
            .map(PlayerStatisticsResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/player/id/{userId}")
    @Operation(
        summary = "Get player statistics by user ID",
        description = "Retrieve comprehensive statistics for a player by their UUID"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Statistics retrieved successfully",
            content = @Content(schema = @Schema(implementation = PlayerStatisticsResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Player not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public ResponseEntity<PlayerStatisticsResponse> getPlayerStatsByUserId(
            @Parameter(description = "User UUID") @PathVariable UUID userId) {
        return statsService.getStatsByUserId(userId)
            .map(PlayerStatisticsResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/player/{playerName}/summary")
    @Operation(
        summary = "Get formatted statistics summary",
        description = "Get a human-readable summary of player statistics with calculated metrics"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Summary retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Player not found")
    })
    public ResponseEntity<PlayerStatisticsService.PlayerStatsSummary> getPlayerStatsSummary(
            @Parameter(description = "Player username") @PathVariable String playerName) {
        PlayerStatisticsService.PlayerStatsSummary summary = statsService.getStatsSummary(playerName);
        if (summary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/search")
    @Operation(
        summary = "Search players by name",
        description = "Search for players whose username contains the query string"
    )
    @ApiResponse(responseCode = "200", description = "Search results returned")
    public ResponseEntity<List<PlayerStatisticsResponse>> searchPlayers(
            @Parameter(description = "Search query string", example = "john")
            @RequestParam String query) {
        List<PlayerStatisticsResponse> results = statsService.searchPlayers(query)
            .stream().map(PlayerStatisticsResponse::from).toList();
        return ResponseEntity.ok(results);
    }



    @GetMapping("/leaderboard")
    @Operation(
        summary = "Get comprehensive leaderboard",
        description = "Returns combined leaderboard data with multiple ranking categories"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard data retrieved successfully")
    public ResponseEntity<PlayerStatisticsService.LeaderboardData> getLeaderboard() {
        PlayerStatisticsService.LeaderboardData leaderboard = statsService.getLeaderboard();
        return ResponseEntity.ok(leaderboard);
    }

    @GetMapping("/leaderboard/winnings")
    @Operation(
        summary = "Top players by total winnings",
        description = "Returns top 10 players ranked by total chips won"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getTopByWinnings() {
        return ResponseEntity.ok(toResponses(statsService.getTopByWinnings()));
    }

    @GetMapping("/leaderboard/hands-won")
    @Operation(
        summary = "Top players by hands won",
        description = "Returns top 10 players ranked by total number of hands won"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getTopByHandsWon() {
        return ResponseEntity.ok(toResponses(statsService.getTopByHandsWon()));
    }

    @GetMapping("/leaderboard/win-rate")
    @Operation(
        summary = "Top players by win rate",
        description = "Returns top 10 players ranked by win percentage (minimum hands required)"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getTopByWinRate() {
        return ResponseEntity.ok(toResponses(statsService.getTopByWinRate()));
    }

    @GetMapping("/leaderboard/biggest-pot")
    @Operation(
        summary = "Top players by biggest pot won",
        description = "Returns top 10 players ranked by their largest single pot win"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getTopByBiggestPot() {
        return ResponseEntity.ok(toResponses(statsService.getTopByBiggestPot()));
    }

    @GetMapping("/leaderboard/win-streak")
    @Operation(
        summary = "Top players by longest win streak",
        description = "Returns top 10 players ranked by their longest consecutive win streak"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getTopByWinStreak() {
        return ResponseEntity.ok(toResponses(statsService.getTopByWinStreak()));
    }

    @GetMapping("/leaderboard/most-active")
    @Operation(
        summary = "Most active players",
        description = "Returns top 10 players ranked by total number of hands played"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getMostActive() {
        return ResponseEntity.ok(toResponses(statsService.getMostActive()));
    }

    @GetMapping("/leaderboard/recently-active")
    @Operation(
        summary = "Recently active players",
        description = "Returns players who have played most recently"
    )
    @ApiResponse(responseCode = "200", description = "Leaderboard retrieved successfully")
    public ResponseEntity<List<PlayerStatisticsResponse>> getRecentlyActive() {
        return ResponseEntity.ok(toResponses(statsService.getRecentlyActive()));
    }

    private static List<PlayerStatisticsResponse> toResponses(
            List<com.truholdem.model.PlayerStatistics> stats) {
        return stats.stream().map(PlayerStatisticsResponse::from).toList();
    }
}
