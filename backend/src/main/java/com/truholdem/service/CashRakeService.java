package com.truholdem.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.model.CashRakeEntry;
import com.truholdem.model.CashTable;
import com.truholdem.repository.CashRakeEntryRepository;

/**
 * Computes and records the rake (house take) from cash-table pots. The rake is a percentage of the pot in basis
 * points (the table's {@code rakeBasisPoints}) with an optional per-pot cap ({@code rakeCap}, 0 = uncapped),
 * and is only taken from a <b>contested</b> pot — a pot that saw a flop (the standard "no-flop, no-drop" rule),
 * so an uncontested pre-flop steal is never raked. The taken amount is recorded as house revenue
 * ({@link CashRakeEntry}); the actual deduction from the winners' payout happens where the pot is awarded
 * (the engine slice). Recording is idempotent on the settling hand/game id.
 */
@Service
public class CashRakeService {

    private static final Logger log = LoggerFactory.getLogger(CashRakeService.class);
    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);
    private static final int RAKE_SCALE = 18;

    private final CashRakeEntryRepository rakeRepository;

    public CashRakeService(CashRakeEntryRepository rakeRepository) {
        this.rakeRepository = rakeRepository;
    }

    /**
     * The rake that would be taken from {@code pot}: {@code 0} for an uncontested pot (no flop), otherwise
     * {@code pot * rakeBps / 10000} rounded down, clamped to {@code rakeCap} (when positive) and never above the
     * pot. Pure — no side effects.
     */
    public BigDecimal computeRake(BigDecimal pot, boolean contested, int rakeBps, BigDecimal rakeCap) {
        if (!contested || pot == null || pot.signum() <= 0 || rakeBps <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rake = pot.multiply(BigDecimal.valueOf(rakeBps))
                .divide(BPS_DIVISOR, RAKE_SCALE, RoundingMode.DOWN);
        if (rakeCap != null && rakeCap.signum() > 0) {
            rake = rake.min(rakeCap);
        }
        return rake.min(pot);
    }

    /**
     * Compute the rake for a settled pot and record it as house revenue, returning the amount taken. Idempotent
     * on {@code idempotencyKey} (the settling hand/game id): a repeat returns the already-recorded rake without
     * adding a second entry. A zero rake (uncontested / no take) is not recorded.
     */
    @Transactional
    public BigDecimal collectRake(CashTable table, BigDecimal pot, boolean contested, String idempotencyKey) {
        CashRakeEntry existing = rakeRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing.getRakeAmount();
        }
        BigDecimal rake = computeRake(pot, contested, table.getRakeBasisPoints(), table.getRakeCap());
        if (rake.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        rakeRepository.save(new CashRakeEntry(table.getId(), table.getAsset(), pot, rake, idempotencyKey));
        log.info("Raked {} {} from a {} pot at cash table {} ({})",
                rake, table.getAsset(), pot, table.getId(), idempotencyKey);
        return rake;
    }

    /** Total rake accrued at a table (house revenue). */
    public BigDecimal houseRevenue(UUID tableId) {
        BigDecimal total = rakeRepository.totalRakeForTable(tableId);
        return total != null ? total : BigDecimal.ZERO;
    }
}
