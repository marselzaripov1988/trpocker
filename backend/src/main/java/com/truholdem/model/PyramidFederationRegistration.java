package com.truholdem.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A player's registration into a {@link PyramidFederation}, recorded before any shard starts. Players are
 * assigned to shards in fill order; when a shard's child pyramid is materialized these rows seed its
 * registrations. One registration per player per federation (DB-enforced).
 */
@Entity
@Table(name = "pyramid_federation_registrations",
        uniqueConstraints = @UniqueConstraint(name = "uq_federation_registration_player",
                columnNames = {"federation_id", "player_id"}))
public class PyramidFederationRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "federation_id", nullable = false)
    private UUID federationId;

    /** The shard this player was assigned to (by fill order). */
    @Column(name = "shard_index", nullable = false)
    private int shardIndex;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "player_name", nullable = false)
    private String playerName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PyramidFederationRegistration() {
    }

    public PyramidFederationRegistration(UUID federationId, int shardIndex, UUID playerId, String playerName) {
        this.federationId = federationId;
        this.shardIndex = shardIndex;
        this.playerId = playerId;
        this.playerName = playerName;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
