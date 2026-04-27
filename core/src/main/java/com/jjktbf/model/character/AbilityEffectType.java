package com.jjktbf.model.character;

/**
 * The finite vocabulary of mechanical effects an ability can apply.
 *
 * Every ability, no matter how narratively unique, reduces to one or more
 * of these 17 primitives. New primitives require a code change; new
 * abilities only require a data change.
 *
 * Parameter field names used in AbilityEffectData per type:
 *
 *  STAT_ADD             stat, intValue
 *  STAT_MULTIPLY        stat, doubleValue
 *  STAT_SET_MAX         stat
 *  STAT_SET_VALUE       stat, intValue
 *  STAT_BONUS_POINTS    intValue            (editor-only: expands point-buy budget)
 *  CE_COST_TO_MINIMUM   moveTag (null = all tags)
 *  CE_COST_MULTIPLY     moveTag (null = all), doubleValue
 *  MOVE_ACCURACY_ADD    moveTag (null = all), intValue
 *  DAMAGE_MULTIPLY      moveTag (null = all), doubleValue
 *  GRANT_MOVE           moveId
 *  BF_CHANCE_ADD        doubleValue         (e.g. 0.05 = +5%)
 *  UNLOCK_TECHNIQUE     stringValue         (technique name to unlock)
 *  MODIFY_DEFENSE       doubleValue         (multiplicative)
 *  MODIFY_AP_BAR        intValue
 *  AUTO_STATUS_APPLY    stringValue (StatusEffectType name), target, timing
 *  BLOCK_MOVE_TAG       moveTag
 *  COST_CE_PER_ROUND    intValue
 */
public enum AbilityEffectType {

    // ── Stat modifiers ────────────────────────────────────────────────────────

    /** Add a flat integer amount to a stat. Can be negative. */
    STAT_ADD,

    /** Multiply a stat by a factor. Applied after all additive mods. */
    STAT_MULTIPLY,

    /** Set a stat to its maximum allowed value (300). */
    STAT_SET_MAX,

    /** Set a stat to a specific integer value, ignoring current value. */
    STAT_SET_VALUE,

    /**
     * Grant bonus points to the point-buy budget in the character editor.
     * Has NO runtime combat effect — editor-only.
     * Example: Six Eyes grants +80 pts to compensate for being unable to
     * spend points normally (since it sets CE Efficiency to max automatically).
     */
    STAT_BONUS_POINTS,

    // ── CE cost modifiers ─────────────────────────────────────────────────────

    /**
     * Force all CE costs (or a specific move tag's costs) to their minimum value.
     * moveTag null = applies to every CE-bearing move.
     */
    CE_COST_TO_MINIMUM,

    /**
     * Multiply CE costs by a factor.
     * e.g. 0.5 halves CE costs, 2.0 doubles them.
     * moveTag null = applies to all CE-bearing moves.
     */
    CE_COST_MULTIPLY,

    // ── Move modifiers ────────────────────────────────────────────────────────

    /** Add a flat bonus to the accuracy roll for moves. moveTag null = all moves. */
    MOVE_ACCURACY_ADD,

    /** Multiply damage dealt by moves. moveTag null = all damaging moves. */
    DAMAGE_MULTIPLY,

    /**
     * Add a specific move to the character's known moves outside the slot system.
     * Used for abilities that grant signature moves unconditionally.
     */
    GRANT_MOVE,

    // ── Black Flash modifiers ─────────────────────────────────────────────────

    /** Add a flat amount to the Black Flash proc chance (as a fraction, e.g. 0.05). */
    BF_CHANCE_ADD,

    // ── Unlock / grant ────────────────────────────────────────────────────────

    /**
     * Grant the character an innate cursed technique by name.
     * Overrides any existing innateTechniqueName.
     * Example: Six Eyes unlocks "Limitless".
     */
    UNLOCK_TECHNIQUE,

    // ── Defensive modifiers ───────────────────────────────────────────────────

    /** Multiply the character's effective Defense stat by a factor. */
    MODIFY_DEFENSE,

    // ── AP bar modifiers ──────────────────────────────────────────────────────

    /** Add a flat integer to the AP bar size. */
    MODIFY_AP_BAR,

    // ── Status effect automation ──────────────────────────────────────────────

    /**
     * Automatically apply a status effect under specific conditions.
     * Parameters: stringValue = StatusEffectType name,
     *             target = SELF or ENEMY,
     *             timing = ROUND_START, FIGHT_START, or ON_HIT.
     */
    AUTO_STATUS_APPLY,

    // ── Restrictions ──────────────────────────────────────────────────────────

    /** Prevent the character from queuing moves with the specified move tag. */
    BLOCK_MOVE_TAG,

    // ── Sustained costs ───────────────────────────────────────────────────────

    /**
     * Drain a fixed amount of CE at the start of each round.
     * Models the sustained cost of always-active techniques (e.g. Infinity).
     */
    COST_CE_PER_ROUND
}
