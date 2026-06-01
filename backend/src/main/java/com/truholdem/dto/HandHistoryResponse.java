package com.truholdem.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.truholdem.model.HandHistory;

/**
 * Read-model (CQRS query side) for hand history. Decouples the REST API from the JPA
 * {@link HandHistory} entity while serializing to the exact same JSON shape the frontend consumes
 * (component names mirror the entity getters). History is post-hand, so hole cards are intentionally
 * present — this is a decoupling boundary, not a masking one.
 */
public record HandHistoryResponse(
        UUID id,
        UUID gameId,
        int handNumber,
        LocalDateTime playedAt,
        int smallBlind,
        int bigBlind,
        int dealerPosition,
        String winnerName,
        String winningHandDescription,
        int finalPot,
        List<PlayerView> players,
        List<ActionView> actions,
        List<CardView> board) {

    public static HandHistoryResponse from(HandHistory h) {
        return new HandHistoryResponse(
                h.getId(),
                h.getGameId(),
                h.getHandNumber(),
                h.getPlayedAt(),
                h.getSmallBlind(),
                h.getBigBlind(),
                h.getDealerPosition(),
                h.getWinnerName(),
                h.getWinningHandDescription(),
                h.getFinalPot(),
                h.getPlayers().stream().map(PlayerView::from).toList(),
                h.getActions().stream().map(ActionView::from).toList(),
                h.getBoard().stream().map(CardView::from).toList());
    }

    public record PlayerView(
            UUID playerId,
            String playerName,
            int startingChips,
            int seatPosition,
            String holeCard1Suit,
            String holeCard1Value,
            String holeCard2Suit,
            String holeCard2Value) {

        public static PlayerView from(HandHistory.HandHistoryPlayer p) {
            return new PlayerView(
                    p.getPlayerId(),
                    p.getPlayerName(),
                    p.getStartingChips(),
                    p.getSeatPosition(),
                    p.getHoleCard1Suit(),
                    p.getHoleCard1Value(),
                    p.getHoleCard2Suit(),
                    p.getHoleCard2Value());
        }
    }

    public record ActionView(
            UUID playerId,
            String playerName,
            String action,
            int amount,
            String phase,
            LocalDateTime timestamp) {

        public static ActionView from(HandHistory.ActionRecord a) {
            return new ActionView(a.playerId(), a.playerName(), a.action(), a.amount(), a.phase(), a.timestamp());
        }
    }

    public record CardView(String suit, String value) {

        public static CardView from(HandHistory.CardRecord c) {
            return new CardView(c.suit(), c.value());
        }
    }
}
