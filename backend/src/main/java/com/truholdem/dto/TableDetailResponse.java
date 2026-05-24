package com.truholdem.dto;

import java.util.List;
import java.util.UUID;

import com.truholdem.model.TournamentTable;

public record TableDetailResponse(
        UUID id,
        int tableNumber,
        List<TablePlayerInfo> players,
        int playerCount,
        boolean isFinalTable,
        boolean isActive,
        UUID currentGameId) {

    public record TablePlayerInfo(
            UUID id,
            String name,
            int chips,
            boolean isBot) {
    }

    public static TableDetailResponse from(
            TournamentTable table,
            List<TablePlayerInfo> players,
            UUID currentGameId) {
        return new TableDetailResponse(
                table.getId(),
                table.getTableNumber(),
                players,
                table.getPlayerCount(),
                table.isFinalTable(),
                table.isActive(),
                currentGameId);
    }
}
