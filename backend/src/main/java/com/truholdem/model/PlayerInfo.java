package com.truholdem.model;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public class PlayerInfo {
    
    @NotBlank(message = "Player name is required")
    @Size(min = 1, max = 50, message = "Player name must be between 1 and 50 characters")
    private String name;
    
    @Min(value = 1, message = "Starting chips must be at least 1")
    @Max(value = 1000000, message = "Starting chips cannot exceed 1,000,000")
    private int startingChips;
    
    @JsonProperty("isBot")
    private boolean isBot;

    /** Optional id to tie this seat to: used as the owner's userId (non-bots), and — only when
     *  {@link #useStableId} — as the player row's primary key. Tournaments set a stable id (registration player
     *  id); single-player passes the user id for ownership only, so the player row gets a fresh per-game id and
     *  repeated /poker/start calls don't clash on players_pkey. */
    private UUID playerId;

    /** When true (default), {@link #playerId} is also used as the player's primary key (tournaments need a stable
     *  id to map seats back to registrations). Single-player sets this false so the human seat gets a fresh
     *  per-game id (its userId still carries ownership), avoiding a players_pkey clash on a repeat /poker/start. */
    private boolean useStableId = true;

    public PlayerInfo() {
    }

    public PlayerInfo(String name, int startingChips, boolean isBot) {
        this.name = name;
        this.startingChips = startingChips;
        this.isBot = isBot;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public boolean isUseStableId() {
        return useStableId;
    }

    public void setUseStableId(boolean useStableId) {
        this.useStableId = useStableId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getStartingChips() {
        return startingChips;
    }

    public void setStartingChips(int startingChips) {
        this.startingChips = startingChips;
    }

    @JsonProperty("isBot")
    public boolean isBot() {
        return isBot;
    }

    @JsonProperty("isBot")
    public void setBot(boolean bot) {
        isBot = bot;
    }

    @Override
    public String toString() {
        return "PlayerInfo{" +
                "name='" + name + '\'' +
                ", startingChips=" + startingChips +
                ", isBot=" + isBot +
                '}';
    }
}
