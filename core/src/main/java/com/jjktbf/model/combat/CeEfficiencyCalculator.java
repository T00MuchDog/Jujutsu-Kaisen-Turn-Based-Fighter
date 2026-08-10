package com.jjktbf.model.combat;

import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.StatScale;
import com.jjktbf.model.move.Move;

/**
 * Computes the actual CE cost of a move from its base CE cost and the wielder's
 * CE Efficiency and CE Output stats.
 *
 * Stat inputs are the RAW 10–300 values straight from {@code CharacterStats};
 * this calculator applies {@link StatScale#scale} exactly once to obtain the
 * scaled combat values the formulas below expect. Callers must NOT pre-scale.
 *
 * <h3>Efficiency (scaled) — reduces cost; baseline 80 = 1.0×</h3>
 * Piecewise linear:
 * <pre>
 *   scaledEff ≤ 80:  effMult = (115 - scaledEff) / 35        // 10 → 3.0×, 80 → 1.0×
 *   scaledEff &gt; 80:  effMult = 1 - 0.0025·(scaledEff - 80)    // 472 → 0.020×
 * </pre>
 *
 * <h3>Output (scaled) — raises cost above the baseline; ≤80 = 1.0×</h3>
 * Flat up to the baseline (output not yet high enough to tax cost), then linear:
 * <pre>
 *   scaledOut ≤ 80:  outMult = 1.0                              // 10 → 1.0×, 80 → 1.0×
 *   scaledOut &gt; 80:  outMult = 1 + (scaledOut - 80) / 196        // 472 → 3.0×
 * </pre>
 *
 * <h3>Final cost</h3>
 * <pre>
 *   rawCost    = baseCeCost × effMult × outMult   (× optional ability multiplier)
 *   actualCost = clamp(rawCost, minCeCost, maxCeCost)
 * </pre>
 *
 * The cost-neutral reference is Efficiency 80 + Output ≤80 (1.0× × 1.0× = 1.0×):
 * such a character pays exactly the move's listed baseCeCost. Raising Output
 * pushes more CE through the move (costlier); raising Efficiency spends it more
 * frugally (cheaper). Efficiency's swing is intentionally far larger than
 * Output's. Ability flags may further multiply the cost or force it to the
 * move's minimum; the move's minCeCost / maxCeCost are hard floors / ceilings.
 */
public final class CeEfficiencyCalculator {

    // Efficiency (scaled) — low branch, scaledEff in [10, 80]
    private static final double EFFICIENCY_LOW_OFFSET  = 115.0;
    private static final double EFFICIENCY_LOW_DIVISOR = 35.0;

    // Efficiency (scaled) — high branch, scaledEff in (80, 472]
    private static final double EFFICIENCY_NEUTRAL     = 80.0;    // 1.0× point (low/high boundary)
    private static final double EFFICIENCY_HIGH_RATE   = 0.0025;  // cost drop per scaled point above 80

    // Output (scaled) — flat 1.0× up to the baseline, then a linear ramp to 3.0×
    private static final double OUTPUT_NEUTRAL      = 80.0;   // ≤ this → 1.0× (output too low to tax cost)
    private static final double OUTPUT_HIGH_DIVISOR = 196.0;  // +2.0× across the 80→472 scaled range

    /** Raw output used when a caller isolates efficiency only: ≤80 → outMult 1.0×. */
    private static final int DEFAULT_RAW_OUTPUT = 80;

    private CeEfficiencyCalculator() {}

    /**
     * Convenience that isolates the Efficiency effect: Output is held at the
     * cost-neutral minimum (raw 10 → 1.0×) and no ability flags are applied.
     *
     * @param move         the move being used
     * @param ceEfficiency RAW CE Efficiency stat (10–300); scaled internally
     * @return             the CE units to drain
     */
    public static int computeActualCost(Move move, int ceEfficiency) {
        return computeActualCost(move, ceEfficiency, DEFAULT_RAW_OUTPUT, null);
    }

    /**
     * Efficiency + Output, no ability flags.
     *
     * @param move         the move being used
     * @param ceEfficiency RAW CE Efficiency stat (10–300); scaled internally
     * @param ceOutput     RAW CE Output stat (10–300); scaled internally
     * @return             the CE units to drain
     */
    public static int computeActualCost(Move move, int ceEfficiency, int ceOutput) {
        return computeActualCost(move, ceEfficiency, ceOutput, null);
    }

    /**
     * Full CE cost calculation.
     *
     * @param move          the move being used
     * @param ceEfficiency  RAW CE Efficiency stat (10–300); scaled internally
     * @param ceOutput      RAW CE Output stat (10–300); scaled internally
     * @param flags         active ability flags (optional CE cost multiplier / force-min); may be null
     * @return              the CE units to drain when this action segment begins
     */
    public static int computeActualCost(
        Move move,
        int ceEfficiency,
        int ceOutput,
        AbilityApplicator.AbilityFlags flags
    ) {
        if (!move.hasCeCost()) return 0;

        // Scale each raw stat exactly once into the combat-scale (10–~472) the
        // formulas expect. Callers pass raw CharacterStats values.
        int scaledEfficiency = StatScale.scale(Math.max(0, ceEfficiency));
        int scaledOutput     = StatScale.scale(Math.max(0, ceOutput));

        double rawCost = move.getBaseCeCost()
                       * efficiencyMultiplier(scaledEfficiency)
                       * outputMultiplier(scaledOutput);

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

    /**
     * Scaled-efficiency → cost multiplier. Baseline 80 = 1.0×; floored at 0 so
     * an abnormally high scaled efficiency can never make the cost negative.
     */
    private static double efficiencyMultiplier(int scaledEfficiency) {
        double eff = scaledEfficiency;
        double multiplier = (eff <= EFFICIENCY_NEUTRAL)
            ? (EFFICIENCY_LOW_OFFSET - eff) / EFFICIENCY_LOW_DIVISOR
            : 1.0 - EFFICIENCY_HIGH_RATE * (eff - EFFICIENCY_NEUTRAL);
        return Math.max(0.0, multiplier);
    }

    /**
     * Scaled-output → cost multiplier. Output at or below the baseline (80) is 1.0×
     * (not yet high enough to affect cost); above 80 it ramps linearly to 3.0× at
     * the scaled maximum (472).
     */
    private static double outputMultiplier(int scaledOutput) {
        if (scaledOutput <= OUTPUT_NEUTRAL) return 1.0;
        return 1.0 + (scaledOutput - OUTPUT_NEUTRAL) / OUTPUT_HIGH_DIVISOR;
    }
}
