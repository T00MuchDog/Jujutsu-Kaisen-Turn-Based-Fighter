package com.jjktbf.model.combat;

import com.jjktbf.model.character.StatScale;

/**
 * Scales a summoner's shikigami CE-upkeep rate by their (scaled) Cursed Energy
 * Efficiency — efficient summoners bleed less CE maintaining shikigami, while
 * inefficient ones bleed more.
 *
 * <p>Stat input is the RAW 10–300 CE Efficiency straight from {@code
 * CharacterStats}; this scaler applies {@link StatScale#scale} exactly once to
 * obtain the scaled value the curve below expects. Callers must NOT pre-scale.
 *
 * <h3>Upkeep multiplier (scaled efficiency) — baseline 80 = 1.0×</h3>
 * Piecewise linear, symmetric in shape to the move-cost efficiency curve but
 * with its own (narrower) swing:
 * <pre>
 *   scaledEff ≤ 80:  mult = 2.0 - (scaledEff - 10) / 70        // 10 → 2.0×, 80 → 1.0×
 *   scaledEff &gt; 80:  mult = 1.0 - 0.8·(scaledEff - 80) / 392    // 80 → 1.0×, 472 → 0.2×
 * </pre>
 *
 * <h3>How it is applied</h3>
 * The multiplier scales the summed per-tick upkeep <b>rate</b> before fractional
 * accumulation, so it changes how fast the upkeep debt accrues. The
 * floor-to-whole-CE drainage and cross-tick fractional carry behave exactly as
 * before, just fed at the scaled rate. The multiplier is clamped to [0.2, 2.0]
 * (the designed anchors) so an out-of-range effective stat can never escape the
 * intended bounds.
 */
public final class SummonUpkeepScaler {

    /** Low anchor: scaled efficiency 10 → 2.0× upkeep (most wasteful summoner). */
    private static final double LOW_STAT         = 10.0;
    private static final double LOW_MULTIPLIER   = 2.0;

    /** Neutral anchor: scaled efficiency 80 → 1.0× upkeep (the global baseline). */
    private static final double NEUTRAL_STAT       = 80.0;
    private static final double NEUTRAL_MULTIPLIER = 1.0;

    /** High anchor: scaled efficiency 472 → 0.2× upkeep (most frugal summoner). */
    private static final double HIGH_STAT       = 472.0;
    private static final double HIGH_MULTIPLIER = 0.2;

    private SummonUpkeepScaler() {}

    /**
     * @param rawCeEfficiency the summoner's RAW CE Efficiency stat (10–300); scaled internally
     * @return the upkeep-rate multiplier, clamped to [0.2, 2.0]
     */
    public static double upkeepMultiplier(int rawCeEfficiency) {
        int scaled = StatScale.scale(Math.max(0, rawCeEfficiency));
        double eff = scaled;
        double multiplier = (eff <= NEUTRAL_STAT)
            ? lerp(LOW_STAT, LOW_MULTIPLIER, NEUTRAL_STAT, NEUTRAL_MULTIPLIER, eff)
            : lerp(NEUTRAL_STAT, NEUTRAL_MULTIPLIER, HIGH_STAT, HIGH_MULTIPLIER, eff);
        return Math.max(HIGH_MULTIPLIER, Math.min(LOW_MULTIPLIER, multiplier));
    }

    /** Linear interpolation of the multiplier at {@code eff} between two anchor points. */
    private static double lerp(double statLo, double multLo,
                               double statHi, double multHi, double eff) {
        double frac = (eff - statLo) / (statHi - statLo);
        return multLo + (multHi - multLo) * frac;
    }
}
