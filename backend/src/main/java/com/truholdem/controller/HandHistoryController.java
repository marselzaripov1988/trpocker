package com.truholdem.controller;

import com.truholdem.config.api.ApiV1Config;
import com.truholdem.dto.ErrorResponse;
import com.truholdem.dto.GameEventLogResponse;
import com.truholdem.dto.HandHistoryResponse;
import com.truholdem.service.GameEventLogService;
import com.truholdem.service.HandHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;


@RestController
@ApiV1Config
@RequestMapping("/history")
@Tag(name = "Hand History", description = "Hand history retrieval, replay data, and history management")
@SecurityRequirement(name = "bearerAuth")
public class HandHistoryController {

    private final HandHistoryService handHistoryService;
    private final GameEventLogService gameEventLogService;

    public HandHistoryController(HandHistoryService handHistoryService,
            GameEventLogService gameEventLogService) {
        this.handHistoryService = handHistoryService;
        this.gameEventLogService = gameEventLogService;
    }

    @GetMapping("/{historyId}")
    @Operation(
        summary = "Get a specific hand history",
        description = "Retrieve detailed history for a specific hand by its UUID"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Hand history retrieved successfully",
            content = @Content(schema = @Schema(implementation = HandHistoryResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Hand history not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public ResponseEntity<HandHistoryResponse> getHandHistory(
            @Parameter(description = "UUID of the hand history") @PathVariable UUID historyId) {
        return handHistoryService.getHandHistory(historyId)
            .map(HandHistoryResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/game/{gameId}")
    @Operation(
        summary = "Get all hands for a game",
        description = "Retrieve all hand histories for a specific game"
    )
    @ApiResponse(responseCode = "200", description = "Hand histories retrieved successfully")
    public ResponseEntity<List<HandHistoryResponse>> getGameHistory(
            @Parameter(description = "UUID of the game") @PathVariable UUID gameId) {
        List<HandHistoryResponse> history = handHistoryService.getGameHistory(gameId)
            .stream().map(HandHistoryResponse::from).toList();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/game/{gameId}/paged")
    @Operation(
        summary = "Get hands for a game with pagination",
        description = "Retrieve paginated hand histories for a specific game"
    )
    @ApiResponse(responseCode = "200", description = "Paginated hand histories retrieved successfully")
    public ResponseEntity<Page<HandHistoryResponse>> getGameHistoryPaged(
            @Parameter(description = "UUID of the game") @PathVariable UUID gameId,
            @Parameter(description = "Page number (0-based)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        Page<HandHistoryResponse> history = handHistoryService.getGameHistory(gameId, page, size)
            .map(HandHistoryResponse::from);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/player/{playerId}")
    @Operation(
        summary = "Get hands where a player participated",
        description = "Retrieve all hand histories where the specified player was involved"
    )
    @ApiResponse(responseCode = "200", description = "Hand histories retrieved successfully")
    public ResponseEntity<List<HandHistoryResponse>> getPlayerHistory(
            @Parameter(description = "UUID of the player") @PathVariable UUID playerId) {
        List<HandHistoryResponse> history = handHistoryService.getPlayerHistory(playerId)
            .stream().map(HandHistoryResponse::from).toList();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/wins/{playerName}")
    @Operation(
        summary = "Get hands where a player won",
        description = "Retrieve all hand histories where the specified player won the pot"
    )
    @ApiResponse(responseCode = "200", description = "Winning hand histories retrieved successfully")
    public ResponseEntity<List<HandHistoryResponse>> getPlayerWins(
            @Parameter(description = "Player username") @PathVariable String playerName) {
        List<HandHistoryResponse> history = handHistoryService.getPlayerWins(playerName)
            .stream().map(HandHistoryResponse::from).toList();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/recent")
    @Operation(
        summary = "Get recent hands",
        description = "Retrieve the most recently played hands across all games"
    )
    @ApiResponse(responseCode = "200", description = "Recent hand histories retrieved successfully")
    public ResponseEntity<List<HandHistoryResponse>> getRecentHands() {
        List<HandHistoryResponse> history = handHistoryService.getRecentHands()
            .stream().map(HandHistoryResponse::from).toList();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/biggest-pots")
    @Operation(
        summary = "Get hands with biggest pots",
        description = "Retrieve hand histories ranked by pot size"
    )
    @ApiResponse(responseCode = "200", description = "Biggest pot hand histories retrieved successfully")
    public ResponseEntity<List<HandHistoryResponse>> getBiggestPots() {
        List<HandHistoryResponse> history = handHistoryService.getBiggestPots()
            .stream().map(HandHistoryResponse::from).toList();
        return ResponseEntity.ok(history);
    }

    @GetMapping("/{historyId}/replay")
    @Operation(
        summary = "Get replay data for a hand",
        description = """
            Generate replay data for hand analysis and visualization.
            
            **Includes:**
            - Action-by-action sequence
            - Board state at each street
            - Pot sizes throughout the hand
            - Player stack changes
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Replay data generated successfully"),
        @ApiResponse(
            responseCode = "404",
            description = "Hand history not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public ResponseEntity<HandHistoryService.ReplayData> getReplayData(
            @Parameter(description = "UUID of the hand history") @PathVariable UUID historyId) {
        HandHistoryService.ReplayData replayData = handHistoryService.generateReplayData(historyId);
        if (replayData == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(replayData);
    }

    @GetMapping("/game/{gameId}/events")
    @Operation(
        summary = "Get the domain-event log for a game",
        description = "Returns the append-only domain-event stream for a game, ordered by sequence "
                + "(audit / event-sourced replay). Populated on the aggregate engine path."
    )
    @ApiResponse(responseCode = "200", description = "Event log retrieved successfully")
    public ResponseEntity<List<GameEventLogResponse>> getGameEvents(
            @Parameter(description = "UUID of the game") @PathVariable UUID gameId) {
        return ResponseEntity.ok(gameEventLogService.eventsForGame(gameId));
    }

    @GetMapping("/game/{gameId}/hand/{handNumber}/events")
    @Operation(
        summary = "Replay a hand from its domain events",
        description = "Returns the ordered domain-event stream for a single hand "
                + "(GameStarted -> PlayerActed... -> PotAwarded -> HandCompleted), reconstructing the "
                + "betting narrative from the event log. Hole cards are not present (never emitted as events)."
    )
    @ApiResponse(responseCode = "200", description = "Hand event stream retrieved successfully")
    public ResponseEntity<List<GameEventLogResponse>> getHandEvents(
            @Parameter(description = "UUID of the game") @PathVariable UUID gameId,
            @Parameter(description = "Hand number within the game") @PathVariable int handNumber) {
        return ResponseEntity.ok(gameEventLogService.eventsForHand(gameId, handNumber));
    }

    @GetMapping("/game/{gameId}/count")
    @Operation(
        summary = "Get hand count for a game",
        description = "Returns the total number of hands played in a game"
    )
    @ApiResponse(responseCode = "200", description = "Hand count retrieved successfully")
    public ResponseEntity<Long> getHandCount(
            @Parameter(description = "UUID of the game") @PathVariable UUID gameId) {
        long count = handHistoryService.getHandCount(gameId);
        return ResponseEntity.ok(count);
    }

    @DeleteMapping("/game/{gameId}")
    @Operation(
        summary = "Delete all history for a game",
        description = "Permanently delete all hand histories for a specific game. **This action cannot be undone.**"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "History deleted successfully"),
        @ApiResponse(
            responseCode = "404",
            description = "Game not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public ResponseEntity<Void> deleteGameHistory(
            @Parameter(description = "UUID of the game") @PathVariable UUID gameId) {
        handHistoryService.deleteGameHistory(gameId);
        return ResponseEntity.noContent().build();
    }
}
