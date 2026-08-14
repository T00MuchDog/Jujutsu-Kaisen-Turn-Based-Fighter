package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.StatScale;

/**
 * Scales a summoned shikigami's RAW base stats according to its summoner's
 * governing stats, so a shikigami's power reflects the sorcerer who manifest it.
 *
 * <p>The scale factor is driven by a single weighted "governing" stat derived
 * from the summoner at a 2:1 ratio between the technique stat and Cursed Energy
 * Output:
 * <ul>
 *   <li><b>Innate-technique summons</b> (the summoning move carries the
 *       {@code INNATE_TECHNIQUE} tag, e.g. Ten Shadows): CTM : CEO at 2:1.</li>
 *   <li><b>Non-innate summons</b>: Jujutsu Skill : CEO at 2:1.</li>
 * </ul>
 *
 * <p>The governing stat is run through {@link StatScale} (the same nonlinear
 * curve every other stat uses), then mapped to a scale factor by a piecewise
 * LINEAR curve anchored at three points:
 * <pre>
 *   governing stat (scaled)   10 (min)   80 (baseline)   472 (max, = StatScale(300))
 *   scale factor              0.5x       1.0x            2.0x
 * </pre>
 * Concretely, with {@code sg = StatScale.scale(governingRaw)}:
 * <ul>
 *   <li>{@code sg <= 80}:  {@code factor = 0.5 + (sg - 10) * 0.5 / 70}</li>
 *   <li>{@code sg >  80}:  {@code factor = 1.0 + (sg - 80) * 1.0 / (472 - 80)}</li>
 * </ul>
 * The factor is clamped to {@code [0.5, 2.0]}.
 *
 * <p>Design intent — the shikigami mirrors its summoner's mastery:
 *   • A baseline (80-governing) summoner reproduces the shikigami's authored
 *     stats exactly (factor 1.0). This is the neutral point.
 *   • A weak summoner field a diminished shikigami (down to half power).
 *   • A peak summoner fields a doubled shikigami — the stat budget of two base
 *     shikigami. The cap (2x, not unbounded) keeps summons from dwarfing
 *     top-tier fighters, and the 2:1 weighting ensures raw Output alone cannot
 *     trivially max out a summon.
 *
 * <p>The factor multiplies each of the shikigami's RAW base stats, after which
 * the normal pipeline (its own abilities, then {@link StatScale} at formula
 * time) runs unchanged. Each scaled stat is clamped to the standard game bounds
 * {@code [10, 300]} (CTM permits an exact {@code 0}) via {@link CharacterStats.Builder}.
 *
 * <p>Example — Max Elephant (raw VIT 95, raw BST 601) summoned by a fighter with
 * maxed CTM and Output (300/300, innate): governing = 300, scaled = 472,
 * factor = 2.0, so the summoned Max Elephant has raw VIT 190 and raw BST 1202.
 */
public final class SummonStatScaler {

    /** Minimum scale factor (summoner at minimum governing stat). */
    public static final double MIN_FACTOR  = 0.5;
    /** Neutral scale factor (summoner at the 80 baseline). */
    public static final double BASE_FACTOR = 1.0;
    /** Maximum scale factor (summoner at peak governing stat). */
    public static final double MAX_FACTOR  = 2.0;

    /** Scaled governing stat at the baseline anchor — {@link StatScale#scale}(80) == 80. */
    private static final int SCALED_BASELINE = StatScale.ANCHOR;
    /** Scaled governing stat at the high anchor — {@link StatScale#scale}(300) == 472. */
    private static final int SCALED_MAX = StatScale.scale(CharacterStats.MAX_STAT);

    private SummonStatScaler() {}

    /**
     * Produce the scaled base stats for a shikigami about to be summoned.
     *
     * @param summonerStats         the summoner's effective stats (read at materialization)
     * @param shikigamiBaseStats    the shikigami definition's authored raw base stats
     * @param innateTechniqueBased  {@code true} if the summoning move is innate-technique
     *                              based (governed by CTM); {@code false} for a non-innate
     *                              summon (governed by Jujutsu Skill)
     * @return                      a new, clamped {@link CharacterStats} with every raw
     *                              stat multiplied by the computed scale factor
     */
    public static CharacterStats scale(
        CharacterStats summonerStats,
        CharacterStats shikigamiBaseStats,
        boolean innateTechniqueBased
    ) {
        return scale(
            summonerStats,
            shikigamiBaseStats,
            innateTechniqueBased,
            BattleStatMode.STANDARD);
    }

    public static CharacterStats scale(
        CharacterStats summonerStats,
        CharacterStats shikigamiBaseStats,
        boolean innateTechniqueBased,
        BattleStatMode statMode
    ) {
        int primary = innateTechniqueBased
            ? summonerStats.getCursedTechniqueMastery()
            : summonerStats.getJujutsuSkill();
        double factor = factorFor(primary, summonerStats.getCursedEnergyOutput(), statMode);
        return scaleStats(shikigamiBaseStats, factor);
    }

    /**
     * Compute the scale factor for a summoner's governing stats. Exposed for
     * unit testing. The governing stat is the 2:1 weighted average of the
     * technique stat and Output, run through {@link StatScale}, then mapped
     * linearly across the {@code 0.5 / 1.0 / 2.0} anchors and clamped.
     *
     * @param primaryStat  the summoner's CTM (innate summon) or Jujutsu Skill (non-innate)
     * @param outputStat   the summoner's Cursed Energy Output
     * @return             scale factor in {@code [MIN_FACTOR, MAX_FACTOR]}
     */
    static double factorFor(int primaryStat, int outputStat) {
        return factorFor(primaryStat, outputStat, BattleStatMode.STANDARD);
    }

    static double factorFor(
        int primaryStat,
        int outputStat,
        BattleStatMode statMode
    ) {
        int governingRaw    = (int) Math.round((2.0 * primaryStat + outputStat) / 3.0);
        int governingScaled = statMode.scale(governingRaw);

        double factor;
        if (governingScaled <= SCALED_BASELINE) {
            // 10 -> 0.5, 80 -> 1.0 (linear)
            double span = SCALED_BASELINE - CharacterStats.MIN_STAT;     // 70
            factor = MIN_FACTOR
                + (governingScaled - CharacterStats.MIN_STAT) * (BASE_FACTOR - MIN_FACTOR) / span;
        } else {
            // 80 -> 1.0, 472 -> 2.0 (linear)
            double span = SCALED_MAX - SCALED_BASELINE;                  // 392
            factor = BASE_FACTOR
                + (governingScaled - SCALED_BASELINE) * (MAX_FACTOR - BASE_FACTOR) / span;
        }
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
    }

    /** Multiply every raw stat by {@code factor} and clamp via the standard Builder. */
    private static CharacterStats scaleStats(CharacterStats s, double factor) {
        return new CharacterStats.Builder()
            .vitality(scaled(s.getVitality(), factor))
            .strength(scaled(s.getStrength(), factor))
            .durability(scaled(s.getDurability(), factor))
            .speed(scaled(s.getSpeed(), factor))
            .cursedEnergyReserves(scaled(s.getCursedEnergyReserves(), factor))
            .cursedEnergyEfficiency(scaled(s.getCursedEnergyEfficiency(), factor))
            .cursedEnergyOutput(scaled(s.getCursedEnergyOutput(), factor))
            .jujutsuSkill(scaled(s.getJujutsuSkill(), factor))
            .combatAbility(scaled(s.getCombatAbility(), factor))
            .cursedTechniqueMastery(scaled(s.getCursedTechniqueMastery(), factor))
            .build();
    }

    private static int scaled(int rawStat, double factor) {
        return (int) Math.round(rawStat * factor);
    }
}
