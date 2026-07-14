package com.jjktbf.model.move;

/**
 * All possible status effects that moves can apply.
 * Each effect is a tag — actual behavior is handled by the combat engine.
 */
public enum StatusEffectType {

    // --- Debilitating ---
    /** Deals damage at the start of each round tick for a duration. */
    POISON,

    /** Reduces the target's Evasion stat for a duration. */
    BIND,

    /** Reduces the target's CE Efficiency (moves cost more CE). */
    CURSED_SEAL,

    /** Prevents the target from using CE-tagged moves for a duration. */
    CE_SUPPRESSION,

    // --- Enhancing (self-applied) ---
    /** Boosts the user's Power for a duration. */
    POWER_UP,

    /** Boosts the user's Defense stat for a duration. */
    DEFENSE_UP,

    /** Boosts the user's Accuracy for a duration. */
    FOCUS,

    /** Boosts the user's Speed (affects next round's AP bar). */
    SPEED_UP,

    /** Raises the user's Cursed Energy Output by a flat amount for the duration. */
    CE_OUTPUT_UP,

    // --- Special ---
    /** Marks the target — certain follow-up moves deal bonus damage to marked targets. */
    MARKED,

    /** Reduces the target's max AP bar for the next round. */
    AP_DRAIN,

    /** Negates the next damaging hit the user receives. */
    BARRIER
}
