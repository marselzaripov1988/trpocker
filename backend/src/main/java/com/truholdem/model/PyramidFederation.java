package com.truholdem.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A "federated" pyramid tournament: a very large field split into {@code shardCount} shards of up to
 * {@code shardSize} players each. Shards fill and run in waves (each shard a normal pyramid down to one
 * winner); once every shard has a winner the federation waits in {@link FederationStatus#AWAITING_FINAL}
 * until an admin schedules the final among the shard winners. The {@code registrationDeadline} may be null
 * (indefinite — gather players for as long as it takes). Play-money for now; real money is a later slice.
 */
@Entity
@Table(name = "pyramid_federations")
public class PyramidFederation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FederationStatus status = FederationStatus.REGISTERING;

    @Column(name = "shard_size", nullable = false)
    private int shardSize;

    @Column(name = "shard_count", nullable = false)
    private int shardCount;

    @Column(name = "seats_per_table", nullable = false)
    private int seatsPerTable;

    @Column(name = "hands_per_round", nullable = false)
    private int handsPerRound;

    /** Registration deadline; {@code null} = indefinite (no auto-cancel; gather for as long as needed). */
    @Column(name = "registration_deadline")
    private Instant registrationDeadline;

    /** Admin-set start time for the final among shard winners; {@code null} until scheduled. */
    @Column(name = "final_scheduled_start")
    private Instant finalScheduledStart;

    /** The child pyramid tournament that runs the final; {@code null} until the final is created. */
    @Column(name = "final_tournament_id")
    private UUID finalTournamentId;

    /** The grand champion (set on completion). */
    @Column(name = "champion_player_id")
    private UUID championPlayerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected PyramidFederation() {
    }

    public PyramidFederation(String name, int shardSize, int shardCount, int seatsPerTable,
            int handsPerRound, Instant registrationDeadline) {
        this.name = name;
        this.shardSize = shardSize;
        this.shardCount = shardCount;
        this.seatsPerTable = seatsPerTable;
        this.handsPerRound = handsPerRound;
        this.registrationDeadline = registrationDeadline;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markShardsRunning() {
        this.status = FederationStatus.SHARDS_RUNNING;
    }

    public void markAwaitingFinal() {
        this.status = FederationStatus.AWAITING_FINAL;
    }

    /** Admin schedules the final's start time (finalists are notified separately). */
    public void scheduleFinal(Instant when) {
        this.finalScheduledStart = when;
        this.status = FederationStatus.FINAL_SCHEDULED;
    }

    public void markFinalRunning(UUID finalTournamentId) {
        this.finalTournamentId = finalTournamentId;
        this.status = FederationStatus.FINAL_RUNNING;
    }

    public void complete(UUID championPlayerId) {
        this.championPlayerId = championPlayerId;
        this.status = FederationStatus.COMPLETED;
    }

    public void cancel() {
        this.status = FederationStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public FederationStatus getStatus() {
        return status;
    }

    public int getShardSize() {
        return shardSize;
    }

    public int getShardCount() {
        return shardCount;
    }

    public int getSeatsPerTable() {
        return seatsPerTable;
    }

    public int getHandsPerRound() {
        return handsPerRound;
    }

    public Instant getRegistrationDeadline() {
        return registrationDeadline;
    }

    public boolean isRegistrationIndefinite() {
        return registrationDeadline == null;
    }

    public Instant getFinalScheduledStart() {
        return finalScheduledStart;
    }

    public UUID getFinalTournamentId() {
        return finalTournamentId;
    }

    public UUID getChampionPlayerId() {
        return championPlayerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
