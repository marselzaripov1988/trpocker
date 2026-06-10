package com.truholdem.model;

import java.math.BigDecimal;
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

    /** Real-money buy-in charged on registration; null/zero = play-money. */
    @Column(name = "crypto_buy_in_amount", precision = 38, scale = 18)
    private BigDecimal cryptoBuyInAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "crypto_buy_in_asset", length = 32)
    private CryptoAsset cryptoBuyInAsset;

    /** House commission on the federation's crypto prize pool, in basis points (e.g. 1000 = 10%), capped at
     *  {@link com.truholdem.model.Tournament#MAX_FEE_BASIS_POINTS} (20%). 0 (default) = no fee. Inherited by
     *  every child shard / final tournament so the cut applies on whichever pool actually pays out. */
    @Column(name = "fee_basis_points", nullable = false)
    private int feeBasisPoints = 0;

    /** Buy-up variant: each shard is a buy-up pyramid where players can buy guaranteed higher-level seats. */
    @Column(name = "buy_up_enabled", nullable = false)
    private boolean buyUpEnabled = false;

    /** Per-federation prize config (real money). Seeded from the {@code app.tournament} defaults at creation and
     *  editable by an admin until payout; {@code null} falls back to the global default at distribution. */
    @Column(name = "shard_winner_ppm")
    private Integer shardWinnerPpm;

    /** Final-table non-champion place shares as comma-separated basis points (index 0 = 2nd place, e.g.
     *  {@code "300,100"} = 3% + 1%); {@code null}/blank falls back to the global default. */
    @Column(name = "final_table_place_bps", length = 200)
    private String finalTablePlaceBps;

    @Column(name = "final_table_rest_bps")
    private Integer finalTableRestBps;

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

    public BigDecimal getCryptoBuyInAmount() {
        return cryptoBuyInAmount;
    }

    public void setCryptoBuyInAmount(BigDecimal cryptoBuyInAmount) {
        this.cryptoBuyInAmount = cryptoBuyInAmount;
    }

    public CryptoAsset getCryptoBuyInAsset() {
        return cryptoBuyInAsset;
    }

    public void setCryptoBuyInAsset(CryptoAsset cryptoBuyInAsset) {
        this.cryptoBuyInAsset = cryptoBuyInAsset;
    }

    public int getFeeBasisPoints() {
        return feeBasisPoints;
    }

    /** @throws IllegalArgumentException if the fee is negative or exceeds 20% (2000 bps). */
    public void setFeeBasisPoints(int feeBasisPoints) {
        if (feeBasisPoints < 0 || feeBasisPoints > Tournament.MAX_FEE_BASIS_POINTS) {
            throw new IllegalArgumentException(
                    "Federation fee must be between 0 and " + Tournament.MAX_FEE_BASIS_POINTS + " bps (20%)");
        }
        this.feeBasisPoints = feeBasisPoints;
    }

    /** House commission taken off a gross pool ({@code gross × feeBasisPoints / 10000}, rounded DOWN). ZERO
     *  for a 0% fee. */
    public BigDecimal houseFeeOn(BigDecimal grossPool) {
        if (feeBasisPoints <= 0 || grossPool == null || grossPool.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return grossPool.multiply(BigDecimal.valueOf(feeBasisPoints))
                .divide(BigDecimal.valueOf(10_000), 18, java.math.RoundingMode.DOWN);
    }

    public boolean isRealMoney() {
        return cryptoBuyInAmount != null && cryptoBuyInAmount.signum() > 0 && cryptoBuyInAsset != null;
    }

    public boolean isBuyUpEnabled() {
        return buyUpEnabled;
    }

    public void setBuyUpEnabled(boolean buyUpEnabled) {
        this.buyUpEnabled = buyUpEnabled;
    }

    public Integer getShardWinnerPpm() {
        return shardWinnerPpm;
    }

    public void setShardWinnerPpm(Integer shardWinnerPpm) {
        this.shardWinnerPpm = shardWinnerPpm;
    }

    public String getFinalTablePlaceBps() {
        return finalTablePlaceBps;
    }

    public void setFinalTablePlaceBps(String finalTablePlaceBps) {
        this.finalTablePlaceBps = finalTablePlaceBps;
    }

    public Integer getFinalTableRestBps() {
        return finalTableRestBps;
    }

    public void setFinalTableRestBps(Integer finalTableRestBps) {
        this.finalTableRestBps = finalTableRestBps;
    }

    /** The configured final-table place shares parsed from the CSV column, or {@code null} when unset/blank. */
    public java.util.List<Integer> finalTablePlaceBpsList() {
        if (finalTablePlaceBps == null || finalTablePlaceBps.isBlank()) {
            return null;
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String s : finalTablePlaceBps.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(Integer.parseInt(t));
            }
        }
        return out.isEmpty() ? null : out;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
