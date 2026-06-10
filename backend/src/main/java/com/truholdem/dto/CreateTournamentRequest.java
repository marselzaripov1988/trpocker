package com.truholdem.dto;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.TournamentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;


public record CreateTournamentRequest(
    
    @NotBlank(message = "Tournament name is required")
    @Size(min = 3, max = 100, message = "Name must be between 3 and 100 characters")
    String name,
    
    @NotNull(message = "Tournament type is required")
    TournamentType type,
    
    @Min(value = 100, message = "Starting chips must be at least 100")
    @Max(value = 100000, message = "Starting chips cannot exceed 100,000")
    int startingChips,
    
    @Min(value = 2, message = "Minimum players must be at least 2")
    int minPlayers,
    
    @Min(value = 2, message = "Maximum players must be at least 2")
    @Max(value = 10000, message = "Maximum players cannot exceed 10,000")
    int maxPlayers,
    
    @Min(value = 0, message = "Buy-in cannot be negative")
    int buyIn,
    
    
    String blindStructureType,
    
    
    Integer levelDurationMinutes,
    
    
    Integer rebuyAmount,
    Integer rebuyDeadlineLevel,
    Integer maxRebuys,
    
    
    Integer addOnAmount,
    
    
    Integer bountyAmount,
    
    
    List<Integer> payoutStructure,

    /** When true, players cannot self-unregister / self-refund — cancellation is admin-only (default false). */
    Boolean unregisterRequiresApproval,

    /** Optional real-money crypto buy-in amount. With {@code cryptoBuyInAsset} set (and &gt; 0) the tournament
     *  is real-money; null/zero = play-money. */
    BigDecimal cryptoBuyInAmount,

    /** Asset of the real-money buy-in; required (together with a positive amount) for a real-money tournament. */
    CryptoAsset cryptoBuyInAsset,

    /** House commission on the crypto prize pool, in basis points (0–2000 = 0–20%). null/0 = no fee. */
    @Min(value = 0, message = "Fee cannot be negative")
    @Max(value = 2000, message = "Fee cannot exceed 2000 bps (20%)")
    Integer feeBasisPoints
) {


    public static CreateTournamentRequest sitAndGo(String name, int buyIn) {
        return new CreateTournamentRequest(
            name,
            TournamentType.SIT_AND_GO,
            1500,
            6,
            9,
            buyIn,
            "TURBO",
            null, null, null, null, null, null, null, false,
            null, null, null
        );
    }
    
    
    public static CreateTournamentRequest freezeout(String name, int buyIn, int maxPlayers) {
        return new CreateTournamentRequest(
            name,
            TournamentType.FREEZEOUT,
            1500,
            Math.min(10, maxPlayers),
            maxPlayers,
            buyIn,
            "STANDARD",
            null, null, null, null, null, null, null, false,
            null, null, null
        );
    }
    
    
    public static CreateTournamentRequest rebuy(String name, int buyIn, int maxPlayers) {
        return new CreateTournamentRequest(
            name,
            TournamentType.REBUY,
            1500,
            Math.min(10, maxPlayers),
            maxPlayers,
            buyIn,
            "STANDARD",
            null,
            buyIn,     
            6,         
            3,         
            buyIn * 2, 
            null,
            null,
            false,
            null, null, null
        );
    }
    
    
    public static CreateTournamentRequest pyramid(String name, int maxPlayers, int seatsPerTable, int handsPerRound) {
        return new CreateTournamentRequest(
            name,
            TournamentType.PYRAMID,
            1000,
            2,
            maxPlayers,
            0,
            "STANDARD",
            null, null, null, null, null, null, null, false,
            null, null, null
        );
    }

    public static CreateTournamentRequest bounty(String name, int buyIn, int bountyAmount, int maxPlayers) {
        return new CreateTournamentRequest(
            name,
            TournamentType.BOUNTY,
            1500,
            Math.min(10, maxPlayers),
            maxPlayers,
            buyIn,
            "STANDARD",
            null, null, null, null, null,
            bountyAmount,
            null,
            false,
            null, null, null
        );
    }
}
