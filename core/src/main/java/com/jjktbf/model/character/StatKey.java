package com.jjktbf.model.character;

import com.jjktbf.model.character.CharacterStats;

/**
 * Canonical enumeration of every character stat.
 *
 * This enum is the single source of truth for:
 *  - Stat display labels (used by editors)
 *  - Stat name aliases used for string-based lookup (prereqs, ability effects)
 *  - Read access from CharacterStats
 *  - Write access to CharacterData
 *
 * Adding a new stat means adding one enum constant here.
 * No other class needs a new switch/case block.
 */
public enum StatKey {

    VITALITY(
        "vitality",
        "Vitality",
        "vitality", "vit"
    ),
    STRENGTH(
        "strength",
        "Strength",
        "strength", "str"
    ),
    DURABILITY(
        "durability",
        "Durability",
        "durability", "dur"
    ),
    SPEED(
        "speed",
        "Speed",
        "speed", "spd"
    ),
    COMBAT_ABILITY(
        "combatAbility",
        "Combat Ability",
        "combatability", "ca"
    ),
    CURSED_ENERGY_RESERVES(
        "cursedEnergyReserves",
        "Cursed Energy Reserves",
        "cursedenergyreserves", "cereserves", "cer"
    ),
    CURSED_ENERGY_EFFICIENCY(
        "cursedEnergyEfficiency",
        "Cursed Energy Efficiency",
        "cursedenergyefficiency", "ceefficiency", "ceeff"
    ),
    CURSED_ENERGY_OUTPUT(
        "cursedEnergyOutput",
        "Cursed Energy Output",
        "cursedenergyoutput", "ceoutput", "ceo"
    ),
    JUJUTSU_SKILL(
        "jujutsuSkill",
        "Jujutsu Skill",
        "jujutsuskill", "js"
    ),
    CURSED_TECHNIQUE_MASTERY(
        "cursedTechniqueMastery",
        "Cursed Technique Mastery",
        "cursedtechniquemastery", "ctmastery", "ctm"
    );

    /** The camelCase field name used in CharacterData (JSON DTO). */
    public final String fieldName;

    /** Human-readable label for display in editors and UI. */
    public final String label;

    /** Normalised aliases (all lowercase, no spaces or underscores) for string lookup. */
    private final String[] aliases;

    StatKey(String fieldName, String label, String... aliases) {
        this.fieldName = fieldName;
        this.label     = label;
        this.aliases   = aliases;
    }

    // =========================================================================
    // Lookup
    // =========================================================================

    /**
     * Find a StatKey from any recognised string alias.
     * Normalises the input (lowercase, strips spaces and underscores) before matching.
     *
     * @throws IllegalArgumentException if no key matches
     */
    public static StatKey fromString(String name) {
        String norm = normalise(name);
        for (StatKey key : values()) {
            for (String alias : key.aliases) {
                if (alias.equals(norm)) return key;
            }
            // Also match the camelCase fieldName directly
            if (normalise(key.fieldName).equals(norm)) return key;
        }
        throw new IllegalArgumentException("Unknown stat name: " + name);
    }

    /** Returns true if the given string resolves to this key. */
    public boolean matches(String name) {
        String norm = normalise(name);
        for (String alias : aliases) {
            if (alias.equals(norm)) return true;
        }
        return normalise(fieldName).equals(norm);
    }

    // =========================================================================
    // Read / Write on domain objects
    // =========================================================================

    /** Read this stat from a CharacterStats instance. */
    public int get(CharacterStats stats) {
        return switch (this) {
            case VITALITY                 -> stats.getVitality();
            case STRENGTH                 -> stats.getStrength();
            case DURABILITY               -> stats.getDurability();
            case SPEED                    -> stats.getSpeed();
            case COMBAT_ABILITY           -> stats.getCombatAbility();
            case CURSED_ENERGY_RESERVES   -> stats.getCursedEnergyReserves();
            case CURSED_ENERGY_EFFICIENCY -> stats.getCursedEnergyEfficiency();
            case CURSED_ENERGY_OUTPUT     -> stats.getCursedEnergyOutput();
            case JUJUTSU_SKILL            -> stats.getJujutsuSkill();
            case CURSED_TECHNIQUE_MASTERY -> stats.getCursedTechniqueMastery();
        };
    }

    /** Read this stat from a CharacterData DTO. */
    public int get(CharacterData data) {
        return switch (this) {
            case VITALITY                 -> data.vitality;
            case STRENGTH                 -> data.strength;
            case DURABILITY               -> data.durability;
            case SPEED                    -> data.speed;
            case COMBAT_ABILITY           -> data.combatAbility;
            case CURSED_ENERGY_RESERVES   -> data.cursedEnergyReserves;
            case CURSED_ENERGY_EFFICIENCY -> data.cursedEnergyEfficiency;
            case CURSED_ENERGY_OUTPUT     -> data.cursedEnergyOutput;
            case JUJUTSU_SKILL            -> data.jujutsuSkill;
            case CURSED_TECHNIQUE_MASTERY -> data.cursedTechniqueMastery;
        };
    }

    /** Write a value to this stat's field on a CharacterData DTO. */
    public void set(CharacterData data, int value) {
        switch (this) {
            case VITALITY                 -> data.vitality               = value;
            case STRENGTH                 -> data.strength               = value;
            case DURABILITY               -> data.durability             = value;
            case SPEED                    -> data.speed                  = value;
            case COMBAT_ABILITY           -> data.combatAbility          = value;
            case CURSED_ENERGY_RESERVES   -> data.cursedEnergyReserves   = value;
            case CURSED_ENERGY_EFFICIENCY -> data.cursedEnergyEfficiency = value;
            case CURSED_ENERGY_OUTPUT     -> data.cursedEnergyOutput     = value;
            case JUJUTSU_SKILL            -> data.jujutsuSkill           = value;
            case CURSED_TECHNIQUE_MASTERY -> data.cursedTechniqueMastery = value;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String normalise(String s) {
        return s.toLowerCase().replace(" ", "").replace("_", "");
    }
}
