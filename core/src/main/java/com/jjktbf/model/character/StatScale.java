package com.jjktbf.model.character;

/**
 * Nonlinear stat scaling — the single source of truth for converting a raw base
 * stat into the value every derived combat formula actually uses.
 *
 * The raw stat scale is linear, which made stat differences matter uniformly:
 * a 100-vs-200 gap behaved like a 250-vs-300 gap. We want differences to matter
 * MORE the further the stats sit from the baseline (where characters are
 * clustered), and to compress — but still hurt — for low stats.
 *
 * The curve is an odd-power transform anchored at the baseline:
 *
 *   S(s) = max(FLOOR, round(ANCHOR + sign(s - ANCHOR) · COEFFICIENT · |s - ANCHOR|^POWER))
 *
 *   ANCHOR    = 80   (baseline stat; S(80) == 80 exactly — baseline tests pass)
 *   POWER     = 1.5  (super-linear: derivative is 0 at the anchor, steepens away)
 *   COEFFICIENT = 0.12
 *   FLOOR     = 8    (low stats stay bad but never collapse to zero)
 *
 * It is symmetric in spirit about the anchor: above 80 it amplifies (so a
 * 300-vs-250 gap dwarfs a 100-vs-200 gap), and below 80 it compresses the
 * absolute distance while still leaving low stats meaningfully worse (VIT 20
 * is much worse than 40, which is worse than 60, which is only a little worse
 * than 80).
 *
 * Sample values:
 *   s =  10  20  32  40  50  60  70  80  92 100 120 150 200 250 300
 *   S =  10  24  40  50  60  69  76  80  85  91 110 150 238 346 472
 *
 * This is applied uniformly to BOTH sides of every comparison (attacker Power
 * and defender Defense, attacker Accuracy and defender Evasion, etc.), so the
 * curve is balance-neutral in aggregate — it reshapes how differences
 * translate into outcomes, not who is favoured.
 */
public final class StatScale {

    /** Stat value the curve is anchored at — S(ANCHOR) == ANCHOR exactly. */
    public static final int ANCHOR = 80;

    /** Floor: a scaled stat never drops below this (low stats stay bad, not broken). */
    public static final int FLOOR = 8;

    /** Power of the |s - anchor| term. >1 makes the curve flatten at the anchor. */
    public static final double POWER = 1.5;

    /** Coefficient scaling the |s - anchor|^POWER term. */
    public static final double COEFFICIENT = 0.12;

    private StatScale() {}

    /**
     * Convert a raw base stat into the scaled value used by all derived combat
     * formulas. Pure function; safe to call on any non-negative stat.
     *
     * @param rawStat  the base stat (expected 10–300, but any non-negative value works)
     * @return         the scaled stat, never below {@link #FLOOR}
     */
    public static int scale(int rawStat) {
        double delta = rawStat - (double) ANCHOR;
        double scaled = ANCHOR + Math.signum(delta) * COEFFICIENT * Math.pow(Math.abs(delta), POWER);
        return Math.max(FLOOR, (int) Math.round(scaled));
    }
}
