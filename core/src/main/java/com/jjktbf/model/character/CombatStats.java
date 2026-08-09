package com.jjktbf.model.character;

/**
 * Derived combat stats — computed from CharacterStats via the agreed formulae.
 * These values are read-only snapshots; they are recomputed whenever base stats change.
 *
 * ============================================================
 * STAT SCALING  (StatScale.java — applied to EVERY base stat first)
 * ============================================================
 * Every base stat is passed through StatScale.scale() before entering any
 * derived formula below. The curve is PIECEWISE, anchored at the baseline 80:
 *
 *   For s <= 80:  S(s) = s                                          (identity / linear)
 *   For s >  80:  S(s) = max(8, round(80 + 0.12 · (s − 80)^1.5))   (super-linear)
 *
 * Linear from 10 to 80 (the Grade 3 → Grade 1 climb feels massive and scales
 * uniformly), flat just above 80 (Grade 1 peers trade near-even — differences
 * are marginal), then steepening toward 300 (heavy hitters pull away faster
 * than the raw gap suggests). So S(20)=20, S(40)=40, S(60)=60, S(80)=80, and
 * above 80 S(150)=150, S(200)=238, S(300)=472. See StatScale for full detail.
 *
 * EXCEPTION: AP bar size uses StatScale.scaleForAp() — a separate curve with
 * lower anchors (G(80)=60, G(300)=300) so AP bars are intentionally smaller.
 * All OTHER formulas below use S(stat).
 *
 * ============================================================
 * FORMULA REFERENCE  (inputs are the SCALED stats S(stat))
 * ============================================================
 *
 *  HP
 *    Baseline (VIT=80)  → 280 HP
 *    Formula: HP = round(S(VIT) * HP_PER_VIT)
 *    HP_PER_VIT = 3.5  (raised from the 2.67 placeholder — longer fights)
 *
 *  AP BAR SIZE
 *    Baseline (SPD=80, CA=80)   → 60   (uses StatScale.scaleForAp, NOT S)
 *    Ratio 15:3 Speed to CombatAbility.
 *    Formula: AP = (G(SPD) * 15 + G(CA) * 3) / AP_DIVISOR
 *      where G = StatScale.scaleForAp: G(10)=10, G(80)=60, G(300)=300
 *    AP_DIVISOR = 18. On the SPD==CA diagonal AP collapses to G(stat), so
 *    AP(80,80)=60 and AP(300,300)=300 exactly.
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
 *    Threshold-based progression off the RAW governing stat (NOT StatScale-scaled —
 *    slots are the one derived value that bypasses StatScale so thresholds land on
 *    clean stat milestones). Both pools share ART_SLOT_TIERS:
 *      stat ≥  :   0   30   45   60   80   100   120   150   180   210   240   270   300
 *      slots   :   2    3    4    5    6     8     9    10    11    12    13    14    15
 *    So: 2 to start, +1 at 30/45/60/80, +2 at 100, +1 at 120/150, then +1 every
 *    30 points up to a max of 15 at 300. Stats above the max tier clamp to 15.
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
 *    BF only rolls on moves that are BlackFlashEligible (PHYSICAL + CURSED_ENERGY,
 *    without an innate or non-innate technique tag).
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
    public static final double HP_PER_VIT = 3.5;

    /**
     * AP bar divisor in the (G(SPD)*15 + G(CA)*3) formula, where G is
     * {@link StatScale#scaleForAp(int)}. Baseline (SPD=80, CA=80) → 60 AP
     * exactly; high stats amplify via the AP-specific curve to 300 at 300/300.
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

    /**
     * Base Black Flash chance (as a fraction).
     */
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

    /**
     * Art-slot progression table — shared by both pools.
     *
     * Each row is {rawStatThreshold, cumulativeSlotsAtOrAboveThatThreshold}. Both
     * pools start with 2 slots at stat 0 and gain more as the governing stat climbs:
     * everyone gets 2 of each to start, then +1 at 30/45/60/80, +2 at 100, +1 at
     * 120/150, then +1 every 30 points up to a max of 15 at 300.
     *
     * Slots read the RAW governing stat (NOT the StatScale-scaled value) — this is
     * the one derived value that intentionally bypasses StatScale, so thresholds
     * land on clean stat milestones instead of distorted intermediate numbers.
     *
     *   stat ≥  :   0   30   45   60   80   100   120   150   180   210   240   270   300
     *   slots   :   2    3    4    5    6     8     9    10    11    12    13    14    15
     *
     * Governing stat: Combat Ability → Combat Arts, Jujutsu Skill → Jujutsu Arts.
     */
    public static final int[][] ART_SLOT_TIERS = {
        {   0,  2 },
        {  30,  3 },
        {  45,  4 },
        {  60,  5 },
        {  80,  6 },
        { 100,  8 },
        { 120,  9 },
        { 150, 10 },
        { 180, 11 },
        { 210, 12 },
        { 240, 13 },
        { 270, 14 },
        { 300, 15 },
    };

    /** Maximum slots a pool can ever reach (last entry of ART_SLOT_TIERS). */
    public static final int MAX_ART_SLOTS = ART_SLOT_TIERS[ART_SLOT_TIERS.length - 1][1];

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
        this(cs, null);
    }

    /** Build derived stats with an optional passive override for Jujutsu Art slots. */
    public CombatStats(CharacterStats cs, Integer jujutsuArtSlotsOverride) {
        this.maxHp                    = computeMaxHp(cs);
        this.maxApBar                 = computeMaxApBar(cs);
        this.accuracy                 = computeAccuracy(cs);
        this.evasion                  = computeEvasion(cs);
        this.maxCursedEnergy          = computeMaxCursedEnergy(cs);
        this.combatArtsSlots         = computeCombatArtsSlots(cs);
        this.jujutsuArtsSlots        = jujutsuArtSlotsOverride == null
            ? computeJujutsuArtsSlots(cs)
            : Math.max(0, Math.min(MAX_ART_SLOTS, jujutsuArtSlotsOverride));
    }

    // -------------------------------------------------------------------------
    // Formula implementations
    // -------------------------------------------------------------------------

    private static int computeMaxHp(CharacterStats cs) {
        return (int) Math.round(StatScale.scale(cs.getVitality()) * HP_PER_VIT);
    }

    private static int computeMaxApBar(CharacterStats cs) {
        // AP uses StatScale.scaleForAp (a lower-baseline curve than S) so that
        // 80/80 → 60 and 300/300 → 300. Speed weighted 15:3 over Combat Ability.
        return (StatScale.scaleForAp(cs.getSpeed()) * 15
              + StatScale.scaleForAp(cs.getCombatAbility()) * 3) / AP_DIVISOR;
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
        return artSlotsFor(cs.getCombatAbility());
    }

    private static int computeJujutsuArtsSlots(CharacterStats cs) {
        return artSlotsFor(cs.getJujutsuSkill());
    }

    /**
     * Art slots granted for a given RAW governing stat, per ART_SLOT_TIERS.
     * Walks the table and returns the cumulative count for the highest threshold
     * met. Stats above the max tier clamp to MAX_ART_SLOTS.
     */
    public static int artSlotsFor(int rawStat) {
        int slots = ART_SLOT_TIERS[0][1];
        for (int[] tier : ART_SLOT_TIERS) {
            if (rawStat >= tier[0]) {
                slots = tier[1];
            } else {
                break;
            }
        }
        return slots;
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
