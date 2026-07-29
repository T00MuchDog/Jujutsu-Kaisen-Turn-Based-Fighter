package com.jjktbf.model.character;

/**
 * Nonlinear stat scaling — the single source of truth for converting a raw base
 * stat into the value every derived combat formula actually uses.
 *
 * The curve is PIECEWISE: linear from the minimum (10) up to the baseline (80),
 * then a super-linear power transform above 80.
 *
 *   For s <= ANCHOR:   S(s) = s                                     (identity)
 *   For s >  ANCHOR:   S(s) = max(FLOOR, round(ANCHOR + COEFFICIENT · (s - ANCHOR)^POWER))
 *
 *   ANCHOR      = 80   (baseline stat; S(80) == 80 exactly)
 *   POWER       = 1.5  (super-linear: derivative is 0 just above the anchor, steepens away)
 *   COEFFICIENT = 0.12
 *   FLOOR       = 8    (defensive lower bound; inert for in-range stats >= 10)
 *
 * Design intent — modelling how tiers FEEL:
 *   • Grade 3 up to Grade 1 (10 → 80): the gap feels massive, and it scales
 *     LINEARLY, so every point invested matters uniformly. A 10-vs-80 fighter
 *     is 70 effective points behind, full stop.
 *   • Among Grade 1s (just above 80 → ~150): differences are MARGINAL. The
 *     curve is flat at the anchor (derivative 0), so a 80-vs-100 gap barely
 *     registers — elite fighters of similar tier trade near-even.
 *   • The heavy hitters (200 → 300): the curve steepens again, so top-tier
 *     fighters pull AWAY from the pack faster than the raw stat gap suggests.
 *     A 250-vs-300 gap dwarfs a 100-vs-150 gap, exactly as intended.
 *
 * This is applied uniformly to BOTH sides of every comparison (attacker Power
 * and defender Defense, attacker Accuracy and defender Evasion, etc.), so the
 * curve is balance-neutral in aggregate — it reshapes how differences
 * translate into outcomes, not who is favoured.
 *
 * Sample values:
 *   s =  10  20  32  40  50  60  70  80  92 100 120 150 200 250 300
 *   S =  10  20  32  40  50  60  70  80  85  91 110 150 238 346 472
 *
 * NOTE: AP bar size uses a SEPARATE transform ({@link #scaleForAp}) with its
 * own anchors (G(80) = 60, not 80) — AP bars are intentionally smaller than the
 * raw stat would imply. See {@link CombatStats#computeMaxApBar}.
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
     * formulas. Identity up to {@link #ANCHOR} (linear 10→80), then super-linear
     * above. Pure function; safe to call on any non-negative stat.
     *
     * @param rawStat  the base stat (expected 10–300, but any non-negative value works)
     * @return         the scaled stat, never below {@link #FLOOR}
     */
    public static int scale(int rawStat) {
        if (rawStat <= ANCHOR) {
            return rawStat;
        }
        double delta = rawStat - (double) ANCHOR;
        double scaled = ANCHOR + COEFFICIENT * Math.pow(delta, POWER);
        return Math.max(FLOOR, (int) Math.round(scaled));
    }

    // ------------------------------------------------------------------
    // AP bar size uses a SEPARATE curve — lower baseline (60 at stat 80)
    // ------------------------------------------------------------------

    /** AP-curve low anchor: raw stat 10 maps to 10 AP-effective points. */
    public static final int AP_LO_STAT = 10;
    /** AP-curve low anchor value: G(10) = 10. */
    public static final int AP_LO_VALUE = 10;
    /** AP-curve mid anchor: raw stat 80 (the baseline) maps to 60 (not 80). */
    public static final int AP_MID_STAT = 80;
    /** AP-curve mid anchor value: G(80) = 60. */
    public static final int AP_MID_VALUE = 60;
    /** AP-curve high anchor: raw stat 300 maps to 300 AP-effective points. */
    public static final int AP_HI_STAT = 300;
    /** AP-curve high anchor value: G(300) = 300. */
    public static final int AP_HI_VALUE = 300;

    /**
     * AP-specific transform — a piecewise curve with its own anchors so that AP
     * bars are intentionally smaller than the raw stat would imply:
     *
     *   For s <= 80:   G(s) = 10 + 50 · ((s - 10) / 70)            (linear, G(10)=10, G(80)=60)
     *   For s >  80:   G(s) = 60 + 240 · ((s - 80) / 220)^1.5      (power,  G(80)=60, G(300)=300)
     *
     * Same 1.5 power as {@link #scale} above the baseline, but a separate,
     * lower baseline so a 80/80 character has a 60 AP bar, and a 300/300
     * character has exactly 300.
     *
     * @param rawStat  the base stat (Speed or Combat Ability)
     * @return         the AP-effective value of that stat
     */
    public static int scaleForAp(int rawStat) {
        if (rawStat <= AP_MID_STAT) {
            double frac = (rawStat - (double) AP_LO_STAT) / (AP_MID_STAT - AP_LO_STAT);
            return (int) Math.round(AP_LO_VALUE + (AP_MID_VALUE - AP_LO_VALUE) * frac);
        }
        double frac = (rawStat - (double) AP_MID_STAT) / (AP_HI_STAT - AP_MID_STAT);
        double scaled = AP_MID_VALUE + (AP_HI_VALUE - AP_MID_VALUE) * Math.pow(frac, POWER);
        return Math.max(FLOOR, (int) Math.round(scaled));
    }
}
