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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * One shard of a {@link PyramidFederation}: a sub-pyramid of up to {@code shardSize} players run to a single
 * winner. Backed by an ordinary child pyramid {@code tournamentId} once it starts, and optionally pinned to a
 * physical {@code nodeGroup} so a shard's tables stay on one set of cluster nodes (data locality).
 */
@Entity
@Table(name = "pyramid_federation_shards",
        uniqueConstraints = @UniqueConstraint(name = "uq_federation_shard_index",
                columnNames = {"federation_id", "shard_index"}))
public class PyramidFederationShard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "federation_id", nullable = false)
    private UUID federationId;

    /** 0-based position of this shard in the federation. */
    @Column(name = "shard_index", nullable = false)
    private int shardIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FederationShardStatus status = FederationShardStatus.PENDING;

    /** The child pyramid tournament running this shard; {@code null} until it starts. */
    @Column(name = "tournament_id")
    private UUID tournamentId;

    /** This shard's winner (the finalist it contributes); {@code null} until complete. */
    @Column(name = "winner_player_id")
    private UUID winnerPlayerId;

    /** Optional cluster node-group this shard is pinned to (physical sharding / data locality). */
    @Column(name = "node_group", length = 64)
    private String nodeGroup;

    @Column(name = "filled_count", nullable = false)
    private int filledCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected PyramidFederationShard() {
    }

    public PyramidFederationShard(UUID federationId, int shardIndex, String nodeGroup) {
        this.federationId = federationId;
        this.shardIndex = shardIndex;
        this.nodeGroup = nodeGroup;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markRegistering() {
        this.status = FederationShardStatus.REGISTERING;
    }

    public void markReady() {
        this.status = FederationShardStatus.READY;
    }

    public void incrementFilled() {
        this.filledCount++;
    }

    public void markRunning(UUID tournamentId) {
        this.tournamentId = tournamentId;
        this.status = FederationShardStatus.RUNNING;
    }

    public void completeWith(UUID winnerPlayerId) {
        this.winnerPlayerId = winnerPlayerId;
        this.status = FederationShardStatus.COMPLETED;
    }

    public void cancel() {
        this.status = FederationShardStatus.CANCELLED;
    }

    public void setFilledCount(int filledCount) {
        this.filledCount = filledCount;
    }

    public void setNodeGroup(String nodeGroup) {
        this.nodeGroup = nodeGroup;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFederationId() {
        return federationId;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public FederationShardStatus getStatus() {
        return status;
    }

    public UUID getTournamentId() {
        return tournamentId;
    }

    public UUID getWinnerPlayerId() {
        return winnerPlayerId;
    }

    public String getNodeGroup() {
        return nodeGroup;
    }

    public int getFilledCount() {
        return filledCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
