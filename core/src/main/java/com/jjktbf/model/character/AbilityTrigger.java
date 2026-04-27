package com.jjktbf.model.character;

/**
 * Trigger conditions for ACTIVE / TRIGGERED abilities.
 *
 * A triggered ability fires its effect primitives automatically when the
 * specified condition is met during combat. The threshold field on
 * AbilityEffectData provides the numeric parameter where applicable.
 */
public enum AbilityTrigger {

    /** Fires when the character's HP drops at or below threshold percent of max HP. */
    ON_HP_BELOW,

    /** Fires when the character lands a Black Flash. No threshold. */
    ON_BF_PROC,

    /** Fires at the start of every round before move selection. No threshold. */
    ON_ROUND_START,

    /** Fires the first time the character's CE pool reaches 0. No threshold. */
    ON_CE_DEPLETED,

    /**
     * Fires when a move with the specified move tag lands a hit on the opponent.
     * sourceValue on the ability should contain the move tag string.
     */
    ON_MOVE_HIT,

    /**
     * Fires when a move with the specified move tag misses.
     * sourceValue on the ability should contain the move tag string.
     */
    ON_MOVE_MISS,

    /**
     * Fires when a status effect of the specified type is applied to this character.
     * sourceValue should contain the StatusEffectType name.
     */
    ON_STATUS_APPLIED
}
