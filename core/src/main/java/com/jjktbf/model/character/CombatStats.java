package com.jjktbf.model.character;

/**
 * Derived combat stats — computed from CharacterStats via the agreed formulae.
 * These values are read-only snapshots; they are recomputed whenever base stats change.
 *
 * ============================================================
 * STAT SCALING  (StatScale.java — applied to EVERY base stat first)
 * ============================================================
 * Every base stat is passed through StatScale.scale() before entering any
 * derived formula below. The curve is a nonlinear transform anchored at 80:
 *
 *   S(s) = max(8, round(80 + sign(s − 80) · 0.12 · |s − 80|^1.5))
 *
 * It flattens at the baseline (S(80) = 80) and steepens away from it, so stat
 * differences matter MORE the further the stats sit from 80 — a 300-vs-250 gap
 * dwarfs a 100-vs-200 gap — while low stats stay meaningfully worse without
 * collapsing (S(20)=24, S(40)=50, S(60)=69). See StatScale for full detail.
 * All formulas below use the SCALED stat values.
 *
 * ============================================================
 * FORMULA REFERENCE  (inputs are the SCALED stats S(stat))
 * ============================================================
 *
 *  HP
 *    Baseline (VIT=80)  → 320 HP
 *    Formula: HP = round(S(VIT) * HP_PER_VIT)
 *    HP_PER_VIT = 4.0  (raised from the 2.67 placeholder — longer fights)
 *
 *  AP BAR SIZE
 *    Baseline (SPD=80, CA=80)   → 80
 *    Ratio 15:3 Speed to CombatAbility.
 *    Formula: AP = (S(SPD) * 15 + S(CA) * 3) / AP_DIVISOR
 *    AP_DIVISOR = 18 → baseline = 80. High stats now amplify via the curve.
 *
 *  ACCURACY  (attacker stat — not a 0-100%, used in hit-roll formula)
 *    Ratio 4:1 CombatAbility to Speed
 *    Formula: ACC = (S(CA) * 4 + S(SPD)) / 5
 *    Baseline: (80*4+80)/5 = 80
 *
 *  EVASION  (defender stat — mirrors accuracy)
 *    Ratio 4:1 Speed to CombatAbility
 *    Formula: EVA = (S(SPD) * 4 + S(CA)) / 5
 *    Baseline: (80*4+80)/5 = 80
 *
 *    Hit chance formula (when attacker ACC == defender EVA, base accuracy 100%):
 *      HIT_CHANCE = BASE_ACCURACY * (ACC / (ACC + EVA * HIT_BALANCE_FACTOR))
 *      HIT_BALANCE_FACTOR chosen so equal stats on a 100% base move → 95% hit rate.
 *      At equal ACC=EVA=80: 1.0 * (80 / (80 + 80*k)) = 0.95 → k ≈ 0.0526
 *      HIT_BALANCE_FACTOR = 1.0/19.0  (exact: 80/(80+80/19) = 95%)
 *
 *  MAX CURSED ENERGY (directly from CE Reserves stat — this IS the CE pool)
 *    CE_MAX = round(S(CE_RESERVES) * CE_POOL_SCALE)
 *    CE_POOL_SCALE = 8  (raised from 5 — more techniques per fight)
 *
 *  MOVE SLOTS (two pools)
 *    1 slot per 20 (scaled) stat points.
 *    Formula: slots = S(stat) / MOVES_PER_STAT_POINTS  (integer division)
 *    MOVES_PER_STAT_POINTS = 20  → baseline 80 → 4 slots
 *    Combat Arts slots   ← Combat Ability   (moves containing the PHYSICAL tag)
 *    Jujutsu Arts slots  ← Jujutsu Skill    (moves without the PHYSICAL tag)
 *    Free moves (isFreeMove = true) never consume a slot.
 *
 *  BLACK FLASH CHANCE
 *    Base: 3% (BF_BASE_CHANCE)
 *    In Black Flash State (BFS): escalates per consecutive BF hit:
 *      Normal → 3%
 *      BFS hit 1 → 10%
 *      BFS hit 2 → 20%
 *      BFS hit 3 → 35%
 *      BFS hit 4+ → 50% (cap)
 *    BF only rolls on moves that are BlackFlashEligible (PHYSICAL + CE component).
 *
 *  DEFENSE (computed combat stat — applied on each hit AFTER defensive moves)
 *    Defense is computed during damage calculation, after BLOCK defensive moves
 *    have already been applied to the incoming damage. It is NOT a raw base stat but
 *    is derived from Durability, CE Reserves, the current CE pool, and CE Output at the
 *    moment of resolution. All inputs are SCALED.
 *
 *    CE reinforcement (the CE contribution to Defense) is CAPPED by CE Output, not by
 *    pool size. While the pool can supply at least the cap, Defense holds at a plateau —
 *    so spending CE early in a round costs no Defense. Only once the pool drops below the
 *    cap does Defense degrade (steeply, since the CE term is weighted 6). This is lore
 *    accurate: a bottomless-CE character (e.g. Yuta, Hakari) still has finite Defense
 *    because their OUTPUT, not their pool size, limits their reinforcement.
 *
 *    Formula: ceFromPool      = S(CE_RESERVES) * (currentCE / maxCE)
 *             reinforcementCap = S(CE_OUTPUT) * DEFENSE_CE_CAP_FACTOR
 *             ceReinforcement  = min(ceFromPool, reinforcementCap)
 *             DEF = (ceReinforcement * 6 + S(DUR) * 2) / 6
 *    Note: DEF is dynamic — recalculated each hit based on current CE.
 *    Pipeline: incoming damage → DEFENSIVE move reduction → Defense stat → final damage.
 *
 *  POWER (move-specific — see PowerCalculator; not a single flat stat)
 *    Physical:          (S(STR) * 4 + S(CA)) / 5
 *    CursedEnergy:      (S(CE_OUT)*3 + S(CE_RES)*2 + S(CE_EFF)) / 6
 *    InnateT:           (CursedEnergy_power * S(CE_OUT) + S(CTM)) / 2  [50:50]
 *    NonInnateT:        (CursedEnergy_power * S(CE_OUT) + S(JS)) / 2   [50:50]
 *    Hybrids: weighted combination — see PowerCalculator.
 *    PHYSICAL category Power is then multiplied by PHYSICAL_POWER_MULTIPLIER
 *    (physical moves are weaker than CE moves at equal base power).
 *
 * ============================================================
 */
public class CombatStats {

    // ------------------------------------------------------------------
    // Tuning constants
    // ------------------------------------------------------------------

    /** HP gained per SCALED point of Vitality. Raised from 2.67 — longer fights. */
    public static final double HP_PER_VIT = 4.0;

    /**
     * AP bar divisor in the (S(SPD)*15 + S(CA)*3) formula.
     * Baseline (SPD=80, CA=80) → 80 AP exactly; high stats amplify via StatScale.
     */
    public static final int AP_DIVISOR = 18;

    /**
     * Factor in hit-roll formula ensuring equal ACC/EVA on a 100%-base move yields
     * a 95% hit chance.
     * Derived: 80 / (80 + 80*k) = 0.95 → k = 1/19.
     */
    public static final double HIT_BALANCE_FACTOR = 1.0 / 19.0;

    /** Multiplier to convert the SCALED CE Reserves stat → CE pool units. Raised from 5. */
    public static final int CE_POOL_SCALE = 8;

    /** PLACEHOLDER: Number of stat points needed per move slot. */
    public static final int MOVES_PER_STAT_POINTS = 20;

    /** Base Black Flash chance (as a fraction). */
    public static final double BF_BASE_CHANCE = 0.03;

    /** BFS escalating BF chances (index = consecutive BF hits in BFS, 0-indexed). */
    public static final double[] BFS_BF_CHANCES = { 0.10, 0.20, 0.35, 0.50 };

    /** Black Flash damage multiplier. */
    public static final double BF_DAMAGE_MULTIPLIER = 2.5;

    /** CE restored on a Black Flash proc (fraction of max CE pool). */
    public static final double BF_CE_RESTORE_FRACTION = 0.05;

    /**
     * PLACEHOLDER: Caps CE reinforcement for Defense at this factor × Cursed Energy Output.
     * While the CE pool can supply at least (CE_OUTPUT × this factor), Defense holds at a
     * plateau — spending CE early costs no Defense. Below that, Defense degrades with the
     * pool. Lore: output (not pool size) limits reinforcement, so bottomless-CE fighters
     * still have finite Defense. Raise to widen the plateau / recover peak; lower to narrow it.
     */
    public static final double DEFENSE_CE_CAP_FACTOR = 0.5;

    /**
     * Power multiplier applied to PHYSICAL-category moves only (after the Power
     * formula, before the damage formula). Physical moves are weaker than CE/technique
     * moves at equal base power — a 40-point physical Power edge should not two-hit
     * kill a peer. < 1 = weaker. Applied in DamageCalculator's power step.
     */
    public static final double PHYSICAL_POWER_MULTIPLIER = 0.85;

    // ------------------------------------------------------------------
    // Derived values computed at construction time
    // ------------------------------------------------------------------

    private final int maxHp;
    private final int maxApBar;
    private final int accuracy;
    private final int evasion;
    private final int maxCursedEnergy;

    // Move slot counts (two pools)
    private final int combatArtsSlots;     // ← Combat Ability  (moves with PHYSICAL tag)
    private final int jujutsuArtsSlots;    // ← Jujutsu Skill   (moves without PHYSICAL tag)

    // -------------------------------------------------------------------------
    // Construction — pass the full CharacterStats
    // -------------------------------------------------------------------------

    public CombatStats(CharacterStats cs) {
        this.maxHp                    = computeMaxHp(cs);
        this.maxApBar                 = computeMaxApBar(cs);
        this.accuracy                 = computeAccuracy(cs);
        this.evasion                  = computeEvasion(cs);
        this.maxCursedEnergy          = computeMaxCursedEnergy(cs);
        this.combatArtsSlots         = computeCombatArtsSlots(cs);
        this.jujutsuArtsSlots        = computeJujutsuArtsSlots(cs);
    }

    // -------------------------------------------------------------------------
    // Formula implementations
    // -------------------------------------------------------------------------

    private static int computeMaxHp(CharacterStats cs) {
        return (int) Math.round(StatScale.scale(cs.getVitality()) * HP_PER_VIT);
    }

    private static int computeMaxApBar(CharacterStats cs) {
        return (StatScale.scale(cs.getSpeed()) * 15
              + StatScale.scale(cs.getCombatAbility()) * 3) / AP_DIVISOR;
    }

    private static int computeAccuracy(CharacterStats cs) {
        return (StatScale.scale(cs.getCombatAbility()) * 4
              + StatScale.scale(cs.getSpeed())) / 5;
    }

    private static int computeEvasion(CharacterStats cs) {
        return (StatScale.scale(cs.getSpeed()) * 4
              + StatScale.scale(cs.getCombatAbility())) / 5;
    }

    private static int computeMaxCursedEnergy(CharacterStats cs) {
        return (int) Math.round(StatScale.scale(cs.getCursedEnergyReserves()) * (double) CE_POOL_SCALE);
    }

    private static int computeCombatArtsSlots(CharacterStats cs) {
        return StatScale.scale(cs.getCombatAbility()) / MOVES_PER_STAT_POINTS;
    }

    private static int computeJujutsuArtsSlots(CharacterStats cs) {
        return StatScale.scale(cs.getJujutsuSkill()) / MOVES_PER_STAT_POINTS;
    }

    /**
     * Compute the Defense value at the moment of a hit.
     *
     * Defense is a combat stat (not a base stat) applied AFTER defensive moves
     * (BLOCK) have already reduced incoming damage.
     *
     * The CE contribution is CAPPED by Cursed Energy Output: while the pool can supply at
     * least (CE_OUTPUT × DEFENSE_CE_CAP_FACTOR), Defense holds at a plateau and spending CE
     * early is free; only once the pool drops below that cap does Defense degrade. This is
     * lore accurate — output, not pool size, limits reinforcement, so even bottomless-CE
     * fighters have finite Defense. Stat enhancements that raise Durability raise Defense;
     * Cursed Energy Output increases raise the reinforcement cap. All stat inputs are
     * passed through StatScale before use.
     *
     * @param cs           the character's (effective) stats
     * @param currentCe    the character's remaining CE pool units at this moment
     * @param maxCe        the character's max CE pool units
     */
    public static int computeDefense(CharacterStats cs, int currentCe, int maxCe) {
        // 6:2 CE reinforcement : Durability; CE reinforcement capped by CE Output.
        // All stat inputs are SCALED via StatScale.
        int scaledCeReserves = StatScale.scale(cs.getCursedEnergyReserves());
        int scaledCeOutput   = StatScale.scale(cs.getCursedEnergyOutput());
        int scaledDurability = StatScale.scale(cs.getDurability());
        double ceFromPool       = (maxCe > 0)
            ? scaledCeReserves * ((double) currentCe / maxCe)
            : 0.0;
        double reinforcementCap = scaledCeOutput * DEFENSE_CE_CAP_FACTOR;
        double ceReinforcement  = Math.min(ceFromPool, reinforcementCap);
        return (int) Math.round((ceReinforcement * 6 + scaledDurability * 2) / 6.0);
    }

    /**
     * Compute the hit chance for an attack.
     * @param attackerAccuracy  attacker's Accuracy stat
     * @param defenderEvasion   defender's Evasion stat
     * @param baseMoveAccuracy  the move's base accuracy (1.0 = 100%)
     * @return hit probability in [0.0, 1.0]
     */
    public static double computeHitChance(int attackerAccuracy, int defenderEvasion, double baseMoveAccuracy) {
        double acc = attackerAccuracy;
        double eva = defenderEvasion;
        double hitRatio = acc / (acc + eva * HIT_BALANCE_FACTOR);
        return Math.min(1.0, baseMoveAccuracy * hitRatio);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getMaxHp()                       { return maxHp; }
    public int getMaxApBar()                    { return maxApBar; }
    public int getAccuracy()                    { return accuracy; }
    public int getEvasion()                     { return evasion; }
    public int getMaxCursedEnergy()             { return maxCursedEnergy; }
    public int getCombatArtsSlots()             { return combatArtsSlots; }
    public int getJujutsuArtsSlots()            { return jujutsuArtsSlots; }

    @Override
    public String toString() {
        return String.format(
            "CombatStats{HP=%d AP=%d ACC=%d EVA=%d CE_MAX=%d CombatArtsSlots=%d JujutsuArtsSlots=%d}",
            maxHp, maxApBar, accuracy, evasion, maxCursedEnergy,
            combatArtsSlots, jujutsuArtsSlots
        );
    }
}
