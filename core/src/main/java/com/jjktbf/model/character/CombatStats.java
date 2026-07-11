package com.jjktbf.model.character;

import com.jjktbf.model.move.MoveCategory;

/**
 * Derived combat stats — computed from CharacterStats via the agreed formulae.
 * These values are read-only snapshots; they are recomputed whenever base stats change.
 *
 * ============================================================
 * FORMULA REFERENCE
 * ============================================================
 *
 *  HP
 *    Baseline (VIT=80)  → 200 HP
 *    Max      (VIT=300) → ~800 HP
 *    Formula: HP = VIT * HP_PER_VIT
 *    HP_PER_VIT placeholder = 2.67  (yields ~214 at 80, ~801 at 300)
 *
 *  AP BAR SIZE
 *    Baseline (SPD=80, CA=80)   → 80
 *    Max      (SPD=300, CA=300) → 300 AP
 *    Ratio 15:3 Speed to CombatAbility.
 *    Formula: AP = (SPD * 15 + CA * 3) / AP_DIVISOR
 *    AP_DIVISOR = 18 → baseline = 80, max = 300.
 *
 *  ACCURACY  (attacker stat — not a 0-100%, used in hit-roll formula)
 *    Ratio 4:1 CombatAbility to Speed
 *    Formula: ACC = (CA * 4 + SPD) / 5
 *    Baseline: (80*4+80)/5 = 80
 *
 *  EVASION  (defender stat — mirrors accuracy)
 *    Ratio 4:1 Speed to CombatAbility
 *    Formula: EVA = (SPD * 4 + CA) / 5
 *    Baseline: (80*4+80)/5 = 80
 *
 *    Hit chance formula (when attacker ACC == defender EVA, base accuracy 100%):
 *      HIT_CHANCE = BASE_ACCURACY * (ACC / (ACC + EVA * HIT_BALANCE_FACTOR))
 *      HIT_BALANCE_FACTOR chosen so equal stats on a 100% base move → 95% hit rate.
 *      At equal ACC=EVA=80: 1.0 * (80 / (80 + 80*k)) = 0.95 → k ≈ 0.0526
 *      HIT_BALANCE_FACTOR = 1.0/19.0  (exact: 80/(80+80/19) = 95%)
 *
 *  MAX CURSED ENERGY (directly from CE Reserves stat — this IS the CE pool)
 *    CE_MAX = CE_RESERVES (the stat value is the pool size)
 *    Placeholder scale: multiply by CE_POOL_SCALE = 5  (baseline 80 → 400 CE units)
 *
 *  MOVE SLOTS (per category)
 *    1 slot per 20 stat points.
 *    Formula: slots = stat / MOVES_PER_STAT_POINTS  (integer division)
 *    MOVES_PER_STAT_POINTS = 20  → baseline 80 → 4 slots
 *    Basic Punch and Basic Block are stored separately and always available.
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
 *    Defense is computed during damage calculation, after PERCENTAGE_BLOCK and FLAT_BLOCK
 *    have already been applied to the incoming damage. It is NOT a raw base stat but
 *    is derived from Durability, CE Reserves, and the current CE pool at the moment of
 *    resolution. Utility moves that raise Durability (e.g. via STATUS_EFFECT DEFENSE_UP)
 *    will indirectly increase Defense.
 *    Formula: DEF = (CE_remaining_fraction * CE_RESERVES * 3 + DUR * 2) / 5
 *    where CE_remaining_fraction = currentCE / maxCE
 *    Note: DEF is dynamic — recalculated each hit based on current CE.
 *    Pipeline: incoming damage → DEFENSIVE move reduction → Defense stat → final damage.
 *
 *  POWER (move-specific — see PowerCalculator; not a single flat stat)
 *    Physical:          (STR * 4 + CA) / 5
 *    CursedEnergy:      (CE_OUT*3 + CE_RES*2 + CE_EFF) / 6
 *    InnateT:           (CursedEnergy_power * CE_OUT + CTM) / 2  [50:50]
 *    NonInnateT:        (CursedEnergy_power * CE_OUT + JS) / 2   [50:50]
 *    Hybrids: weighted combination — see PowerCalculator.
 *
 * ============================================================
 */
public class CombatStats {

    // ------------------------------------------------------------------
    // Tuning constants (all marked PLACEHOLDER — replace when balancing)
    // ------------------------------------------------------------------

    /** PLACEHOLDER: HP gained per point of Vitality. */
    public static final double HP_PER_VIT = 2.67;

    /**
     * AP bar divisor in the (SPD*15 + CA*3) formula.
     * Derived so baseline (SPD=80, CA=80) → 80 AP:
     *   (80*15 + 80*3) / 18 = 1440/18 = 80. Exact.
     * Max (SPD=300, CA=300) → 300 AP:
     *   (300*15 + 300*3) / 18 = 5400/18 = 300.
     */
    public static final int AP_DIVISOR = 18;

    /** No base constant needed — formula is exact at baseline. */
    public static final int AP_BASE_CONSTANT = 0;

    /**
     * PLACEHOLDER: Factor in hit-roll formula ensuring equal ACC/EVA on a 100%-base
     * move yields a 95% hit chance.
     * Derived: 80 / (80 + 80*k) = 0.95 → k = 1/19.
     */
    public static final double HIT_BALANCE_FACTOR = 1.0 / 19.0;

    /** PLACEHOLDER: Multiplier to convert CE Reserves stat → CE pool units. */
    public static final int CE_POOL_SCALE = 5;

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

    // ------------------------------------------------------------------
    // Derived values computed at construction time
    // ------------------------------------------------------------------

    private final int maxHp;
    private final int maxApBar;
    private final int accuracy;
    private final int evasion;
    private final int maxCursedEnergy;

    // Move slot counts
    private final int physicalMoveSlots;
    private final int jujutsuTechniqueSlots;   // also governs CE-tagged move slots
    private final int cursedTechniqueSlots;

    // Cached physical and CE power components (move-type power uses PowerCalculator)
    private final int physicalPowerComponent;
    private final int cursedEnergyPowerComponent;

    // -------------------------------------------------------------------------
    // Construction — pass the full CharacterStats
    // -------------------------------------------------------------------------

    public CombatStats(CharacterStats cs) {
        this.maxHp                    = computeMaxHp(cs);
        this.maxApBar                 = computeMaxApBar(cs);
        this.accuracy                 = computeAccuracy(cs);
        this.evasion                  = computeEvasion(cs);
        this.maxCursedEnergy          = computeMaxCursedEnergy(cs);
        this.physicalMoveSlots        = computePhysicalSlots(cs);
        this.jujutsuTechniqueSlots    = computeJujutsuSlots(cs);
        this.cursedTechniqueSlots     = computeCursedTechniqueSlots(cs);
        this.physicalPowerComponent   = computePhysicalPower(cs);
        this.cursedEnergyPowerComponent = computeCursedEnergyPower(cs);
    }

    // -------------------------------------------------------------------------
    // Formula implementations
    // -------------------------------------------------------------------------

    private static int computeMaxHp(CharacterStats cs) {
        return (int) Math.round(cs.getVitality() * HP_PER_VIT);
    }

    private static int computeMaxApBar(CharacterStats cs) {
        return (cs.getSpeed() * 15 + cs.getCombatAbility() * 3) / AP_DIVISOR + AP_BASE_CONSTANT;
    }

    private static int computeAccuracy(CharacterStats cs) {
        return (cs.getCombatAbility() * 4 + cs.getSpeed()) / 5;
    }

    private static int computeEvasion(CharacterStats cs) {
        return (cs.getSpeed() * 4 + cs.getCombatAbility()) / 5;
    }

    private static int computeMaxCursedEnergy(CharacterStats cs) {
        return cs.getCursedEnergyReserves() * CE_POOL_SCALE;
    }

    private static int computePhysicalSlots(CharacterStats cs) {
        return cs.getCombatAbility() / MOVES_PER_STAT_POINTS;
    }

    private static int computeJujutsuSlots(CharacterStats cs) {
        return cs.getJujutsuSkill() / MOVES_PER_STAT_POINTS;
    }

    private static int computeCursedTechniqueSlots(CharacterStats cs) {
        return cs.getCursedTechniqueMastery() / MOVES_PER_STAT_POINTS;
    }

    private static int computePhysicalPower(CharacterStats cs) {
        return (cs.getStrength() * 4 + cs.getCombatAbility()) / 5;
    }

    private static int computeCursedEnergyPower(CharacterStats cs) {
        // 3:2:1 — CE_Output : CE_Reserves : CE_Efficiency
        return (cs.getCursedEnergyOutput() * 3
              + cs.getCursedEnergyReserves() * 2
              + cs.getCursedEnergyEfficiency()) / 6;
    }

    /**
     * Compute the dynamic Defense value at the moment of a hit.
     * @param cs           the character's base stats
     * @param currentCe    the character's remaining CE pool units at this moment
     * @param maxCe        the character's max CE pool units
     */
    /**
     * Compute the Defense value at the moment of a hit.
     *
     * Defense is a combat stat (not a base stat) applied AFTER defensive moves
     * (PERCENTAGE_BLOCK / FLAT_BLOCK) have already reduced incoming damage.
     * It is derived from Durability and the fraction of CE Reserves remaining.
     * As CE is spent during a round, Defense falls — a depleted fighter is easier to damage.
     * Stat enhancements that increase Durability (e.g. from utility moves) raise Defense.
     */
    public static int computeDefense(CharacterStats cs, int currentCe, int maxCe) {
        // 3:2 RemainingCE_Reserves : Durability
        double ceReserveFraction = (maxCe > 0) ? (double) currentCe / maxCe : 0.0;
        double scaledCeReserves  = cs.getCursedEnergyReserves() * ceReserveFraction;
        return (int) Math.round((scaledCeReserves * 3 + cs.getDurability() * 2) / 5.0);
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

    /**
     * Compute how many move slots a hybrid category grants.
     * Hybrid slots are gated by the lesser of the relevant governing stats.
     */
    public int hybridSlots(CharacterStats cs, MoveCategory category) {
        return switch (category.getSlotStat()) {
            case LESSER_CA_JS       -> Math.min(cs.getCombatAbility(), cs.getJujutsuSkill()) / MOVES_PER_STAT_POINTS;
            case LESSER_CA_CTM      -> Math.min(cs.getCombatAbility(), cs.getCursedTechniqueMastery()) / MOVES_PER_STAT_POINTS;
            case LESSER_CTM_JS      -> Math.min(cs.getCursedTechniqueMastery(), cs.getJujutsuSkill()) / MOVES_PER_STAT_POINTS;
            case LESSER_ALL_THREE   -> Math.min(cs.getCombatAbility(),
                                         Math.min(cs.getCursedTechniqueMastery(), cs.getJujutsuSkill())) / MOVES_PER_STAT_POINTS;
            default -> 0;
        };
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getMaxHp()                       { return maxHp; }
    public int getMaxApBar()                    { return maxApBar; }
    public int getAccuracy()                    { return accuracy; }
    public int getEvasion()                     { return evasion; }
    public int getMaxCursedEnergy()             { return maxCursedEnergy; }
    public int getPhysicalMoveSlots()           { return physicalMoveSlots; }
    public int getJujutsuTechniqueSlots()       { return jujutsuTechniqueSlots; }
    public int getCursedTechniqueSlots()        { return cursedTechniqueSlots; }
    public int getPhysicalPowerComponent()      { return physicalPowerComponent; }
    public int getCursedEnergyPowerComponent()  { return cursedEnergyPowerComponent; }

    @Override
    public String toString() {
        return String.format(
            "CombatStats{HP=%d AP=%d ACC=%d EVA=%d CE_MAX=%d PhysSlots=%d JJSlots=%d CTSlots=%d}",
            maxHp, maxApBar, accuracy, evasion, maxCursedEnergy,
            physicalMoveSlots, jujutsuTechniqueSlots, cursedTechniqueSlots
        );
    }
}
