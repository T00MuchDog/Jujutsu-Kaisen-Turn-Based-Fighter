package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.move.Move;

/**
 * Computes the actual CE cost of a move after applying the character's CE Efficiency stat.
 *
 * Baseline efficiency = 80 (neutral — no change to base cost).
 * Below 80: cost increases.
 * Above 80: cost decreases.
 *
 * Formula (PLACEHOLDER — tune during balance pass):
 *   efficiencyFactor = BASELINE / CE_EFFICIENCY_STAT
 *   rawCost = baseCeCost * efficiencyFactor
 *   actualCost = clamp(rawCost, move.minCeCost, move.maxCeCost)
 *
 * At baseline (eff=80): factor = 80/80 = 1.0 → no change.
 * At max (eff=300):     factor = 80/300 ≈ 0.267 → cost is ~27% of base.
 * At min (eff=10):      factor = 80/10  = 8.0   → cost is 8x base.
 * Hard floor/ceiling from move.minCeCost / move.maxCeCost prevents extremes.
 */
public final class CeEfficiencyCalculator {

    private static final int BASELINE_EFFICIENCY = CharacterStats.BASELINE; // 80

    private CeEfficiencyCalculator() {}

    /**
     * Compute the actual CE cost for a move given the user's CE Efficiency stat.
     *
     * @param move      the move being used
     * @param ceEfficiency the user's CE Efficiency stat value
     * @return          the CE units to drain when this action segment begins
     */
    public static int computeActualCost(Move move, int ceEfficiency) {
        return computeActualCost(move, ceEfficiency, null);
    }

    public static int computeActualCost(
        Move move,
        int ceEfficiency,
        AbilityApplicator.AbilityFlags flags
    ) {
        if (move.getBaseCeCost() == 0) return 0; // non-CE move

        int safeEfficiency = Math.max(1, ceEfficiency);
        double factor  = (double) BASELINE_EFFICIENCY / safeEfficiency;
        double rawCost = move.getBaseCeCost() * factor;

        if (flags != null) {
            rawCost *= flags.ceCostMultiplierFor(move);
        }

        // Clamp to the move's hard min/max
        int clamped = Math.max(move.getMinCeCost(), Math.min(move.getMaxCeCost(), (int) Math.round(rawCost)));
        if (flags != null && flags.forcesMinimumCeCost(move)) {
            return move.getMinCeCost();
        }
        return clamped;
    }
}
