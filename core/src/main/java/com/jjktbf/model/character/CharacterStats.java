package com.jjktbf.model.character;

/**
 * Raw character stats as set by character definition or the character builder.
 * These are the "Elden Ring" style base stats that drive all derived combat stats.
 *
 * All stats share the same range:
 *   Min: 10, Baseline: 80, Max: 300
 */
public class CharacterStats {

    public static final int MIN_STAT    = 10;
    public static final int BASELINE    = 80;
    public static final int MAX_STAT    = 300;

    // --- Physical ---
    /** Determines HP. Primary survival stat. */
    private final int vitality;

    /** Physical striking power. Primary driver of Physical move Power. */
    private final int strength;

    /** Physical resilience. Contributes to Defense. */
    private final int durability;

    /** Movement and reaction speed. Drives AP bar size, Accuracy, and Evasion. */
    private final int speed;

    // --- Cursed Energy ---
    /** Total pool of cursed energy available in a fight. No mid-fight regeneration. */
    private final int cursedEnergyReserves;

    /**
     * Reduces the cursed energy cost of CE-tagged moves.
     * Baseline 80 = neutral cost. Below 80 increases cost; above 80 decreases cost.
     */
    private final int cursedEnergyEfficiency;

    /** Raw cursed energy output. Primary driver of CE move Power. */
    private final int cursedEnergyOutput;

    // --- Jujutsu ---
    /**
     * Governs learned non-innate techniques (barriers, RCT, etc.),
     * determines number of Jujutsu Technique & CE move slots,
     * and scales Jujutsu Technique tagged move stats.
     * Also contributes to total AP bar size.
     */
    private final int jujutsuSkill;

    /**
     * General combat aptitude.
     * Drives number of Physical move slots, Accuracy, AP bar size,
     * and scales Basic/Physical tagged moves.
     */
    private final int combatAbility;

    /**
     * Only meaningful for characters with an innate cursed technique.
     * Scales Cursed Technique tagged moves and combines with Jujutsu Skill
     * for Domain Expansion / Maximum Technique potency.
     * Determines number of Cursed Technique move slots.
     */
    private final int cursedTechniqueMastery;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private CharacterStats(Builder builder) {
        this.vitality               = clampGameRules(builder.vitality);
        this.strength               = clampGameRules(builder.strength);
        this.durability             = clampGameRules(builder.durability);
        this.speed                  = clampGameRules(builder.speed);
        this.cursedEnergyReserves   = clampGameRules(builder.cursedEnergyReserves);
        this.cursedEnergyEfficiency = clampGameRules(builder.cursedEnergyEfficiency);
        this.cursedEnergyOutput     = clampGameRules(builder.cursedEnergyOutput);
        this.jujutsuSkill           = clampGameRules(builder.jujutsuSkill);
        this.combatAbility          = clampGameRules(builder.combatAbility);
        this.cursedTechniqueMastery = clampGameRules(builder.cursedTechniqueMastery);
    }

    /**
     * Unclamped constructor used by AbilityApplicator when ability effects push
     * stats outside normal game bounds (e.g. STAT_SET_MIN → 0).
     * Values are only constrained to be non-negative (no stat can be below 0).
     */
    CharacterStats(int vit, int str, int dur, int spd,
                   int ceRes, int ceEff, int ceOut,
                   int js, int ca, int ctm) {
        this.vitality               = Math.max(0, vit);
        this.strength               = Math.max(0, str);
        this.durability             = Math.max(0, dur);
        this.speed                  = Math.max(0, spd);
        this.cursedEnergyReserves   = Math.max(0, ceRes);
        this.cursedEnergyEfficiency = Math.max(0, ceEff);
        this.cursedEnergyOutput     = Math.max(0, ceOut);
        this.jujutsuSkill           = Math.max(0, js);
        this.combatAbility          = Math.max(0, ca);
        this.cursedTechniqueMastery = Math.max(0, ctm);
    }

    /**
     * Game-rules clamp: enforces MIN_STAT–MAX_STAT.
     * Used by the normal Builder (character creator / editor).
     */
    private static int clampGameRules(int value) {
        return Math.max(MIN_STAT, Math.min(MAX_STAT, value));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getVitality()               { return vitality; }
    public int getStrength()               { return strength; }
    public int getDurability()             { return durability; }
    public int getSpeed()                  { return speed; }
    public int getCursedEnergyReserves()   { return cursedEnergyReserves; }
    public int getCursedEnergyEfficiency() { return cursedEnergyEfficiency; }
    public int getCursedEnergyOutput()     { return cursedEnergyOutput; }
    public int getJujutsuSkill()           { return jujutsuSkill; }
    public int getCombatAbility()          { return combatAbility; }
    public int getCursedTechniqueMastery() { return cursedTechniqueMastery; }

    /**
     * Look up a stat by any recognised name alias.
     * Delegates to StatKey — the single source of truth for stat name mapping.
     */
    public int getByName(String statName) {
        return StatKey.fromString(statName).get(this);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static class Builder {
        private int vitality               = BASELINE;
        private int strength               = BASELINE;
        private int durability             = BASELINE;
        private int speed                  = BASELINE;
        private int cursedEnergyReserves   = BASELINE;
        private int cursedEnergyEfficiency = BASELINE;
        private int cursedEnergyOutput     = BASELINE;
        private int jujutsuSkill           = BASELINE;
        private int combatAbility          = BASELINE;
        private int cursedTechniqueMastery = BASELINE;

        public Builder vitality(int v)               { this.vitality               = v; return this; }
        public Builder strength(int v)               { this.strength               = v; return this; }
        public Builder durability(int v)             { this.durability             = v; return this; }
        public Builder speed(int v)                  { this.speed                  = v; return this; }
        public Builder cursedEnergyReserves(int v)   { this.cursedEnergyReserves   = v; return this; }
        public Builder cursedEnergyEfficiency(int v) { this.cursedEnergyEfficiency = v; return this; }
        public Builder cursedEnergyOutput(int v)     { this.cursedEnergyOutput     = v; return this; }
        public Builder jujutsuSkill(int v)           { this.jujutsuSkill           = v; return this; }
        public Builder combatAbility(int v)          { this.combatAbility          = v; return this; }
        public Builder cursedTechniqueMastery(int v) { this.cursedTechniqueMastery = v; return this; }

        public CharacterStats build() { return new CharacterStats(this); }
    }

    @Override
    public String toString() {
        return String.format(
            "CharacterStats{VIT=%d STR=%d DUR=%d SPD=%d CE_RES=%d CE_EFF=%d CE_OUT=%d JS=%d CA=%d CTM=%d}",
            vitality, strength, durability, speed,
            cursedEnergyReserves, cursedEnergyEfficiency, cursedEnergyOutput,
            jujutsuSkill, combatAbility, cursedTechniqueMastery
        );
    }
}
