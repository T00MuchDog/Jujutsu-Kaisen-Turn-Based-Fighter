package com.jjktbf.model.character;

/**
 * The finite vocabulary of mechanical effects an ability can apply.
 *
 * Every ability reduces to one or more of these primitives.
 * New primitives require a code change; new abilities only require a data change.
 *
 * Design rules:
 *  - Negative intValue / doubleValue is allowed and represents downsides.
 *  - Division is represented as STAT_DIVIDE (divides by doubleValue).
 *  - No hard stat floor/ceiling is enforced by the engine — abilities can push
 *    stats to 0 or beyond normal game limits.
 *  - STAT_SET_MIN sets a stat to 0 (displayed as N/A in the UI).
 *
 * Parameter field usage per type (see AbilityEffectData for field names):
 *
 *  STAT_ADD                  stat, intValue         (negative = downside)
 *  STAT_MULTIPLY             stat, doubleValue       (< 1.0 = reduction, > 1.0 = boost)
 *  STAT_DIVIDE               stat, doubleValue       (divides stat; 2.0 = halve)
 *  STAT_SET_VALUE            stat, intValue          (any integer, 0 = N/A)
 *  STAT_SET_MIN              stat                    (forces stat to 0 / N/A)
 *  STAT_BONUS_POINTS         intValue                (editor-only, no runtime effect)
 *  CE_COST_TO_MINIMUM        moveTag (null = all)
 *  CE_COST_MULTIPLY          moveTag (null = all), doubleValue
 *  MOVE_ACCURACY_ADD         moveTag (null = all), intValue      (negative = penalty)
 *  MOVE_ACCURACY_MULTIPLY    moveTag (null = all), doubleValue   (own accuracy multiplier)
 *  OPPONENT_ACCURACY_ADD     moveTag (null = all), intValue      (modifies opponent's accuracy)
 *  OPPONENT_ACCURACY_MULTIPLY moveTag (null = all), doubleValue  (multiplies opponent's accuracy)
 *  DAMAGE_MULTIPLY           moveTag (null = all), doubleValue
 *  GRANT_MOVE                moveId
 *  BF_CHANCE_ADD             doubleValue             (negative = reduces BF chance)
 *  UNLOCK_TECHNIQUE          stringValue             (technique name)
 *  MODIFY_DEFENSE            doubleValue             (multiplicative)
 *  MODIFY_AP_BAR             intValue                (negative = shrinks AP bar)
 *  AUTO_STATUS_APPLY         stringValue, target, timing
 *  LOCK_MOVE_TAG             moveTag
 *    Passive: prevents character from using/learning moves with this tag.
 *    Active:  temporarily removes those moves from the timeline this round.
 *  COST_CE_PER_ROUND         intValue
 */
public enum AbilityEffectType {

    // ── Stat modifiers ────────────────────────────────────────────────────────

    /** Add a flat integer to a stat. Negative values are valid (downside abilities). */
    STAT_ADD,

    /** Multiply a stat by a factor. Applied after all additive mods. < 1.0 reduces it. */
    STAT_MULTIPLY,

    /** Divide a stat by a factor. Equivalent to STAT_MULTIPLY with 1/factor. */
    STAT_DIVIDE,

    /** Set a stat to any specific integer value (0 = N/A in the UI, no floor enforced). */
    STAT_SET_VALUE,

    /**
     * Set a stat to 0, displaying it as N/A.
     * Useful for abilities that completely remove a capability
     * (e.g. an ability that removes all cursed energy from a character).
     */
    STAT_SET_MIN,

    /**
     * Grant bonus points to the point-buy budget in the character creator.
     * Has NO runtime combat effect — editor/creator only.
     */
    STAT_BONUS_POINTS,

    // ── CE cost modifiers ─────────────────────────────────────────────────────

    /** Force CE costs to their move minimum. moveTag null = all moves. */
    CE_COST_TO_MINIMUM,

    /** Multiply CE costs. < 1.0 reduces cost, > 1.0 increases cost. */
    CE_COST_MULTIPLY,

    // ── Move accuracy modifiers ───────────────────────────────────────────────

    /** Add a flat bonus/penalty to this character's accuracy on moves. */
    MOVE_ACCURACY_ADD,

    /** Multiply this character's accuracy on moves by a factor. */
    MOVE_ACCURACY_MULTIPLY,

    /**
     * Add a flat bonus/penalty to the OPPONENT's accuracy.
     * Negative intValue reduces opponent accuracy (e.g. Infinity makes attacks miss).
     */
    OPPONENT_ACCURACY_ADD,

    /**
     * Multiply the OPPONENT's accuracy by a factor.
     * 0.5 = opponent has half accuracy. Applied on top of additive changes.
     */
    OPPONENT_ACCURACY_MULTIPLY,

    // ── Damage modifiers ──────────────────────────────────────────────────────

    /** Multiply damage dealt by moves. moveTag null = all damaging moves. */
    DAMAGE_MULTIPLY,

    // ── Move grants ───────────────────────────────────────────────────────────

    /** Add a specific move to the character's known moves outside the slot system. */
    GRANT_MOVE,

    // ── Black Flash modifiers ─────────────────────────────────────────────────

    /** Add to Black Flash proc chance (as a fraction). Negative = reduces BF chance. */
    BF_CHANCE_ADD,

    // ── Unlock / grant ────────────────────────────────────────────────────────

    /** Grant an innate cursed technique by name. Overrides any existing technique. */
    UNLOCK_TECHNIQUE,

    // ── Defensive modifiers ───────────────────────────────────────────────────

    /** Multiply the character's effective Defense value. */
    MODIFY_DEFENSE,

    // ── AP bar modifiers ──────────────────────────────────────────────────────

    /** Add a flat amount to the AP bar size. Negative shrinks it. */
    MODIFY_AP_BAR,

    // ── Status effect automation ──────────────────────────────────────────────

    /**
     * Automatically apply a status effect.
     * Parameters: stringValue = StatusEffectType name,
     *             target = SELF or ENEMY,
     *             timing = FIGHT_START, ROUND_START, or ON_HIT.
     */
    AUTO_STATUS_APPLY,

    // ── Move tag locking ──────────────────────────────────────────────────────

    /**
     * Lock out moves with the specified tag.
     *
     * PASSIVE: prevents the character from using (and in the character creator,
     *          learning) moves with this tag. Stored in AbilityFlags.lockedMoveTags.
     *
     * ACTIVE / TRIGGERED: temporarily removes queued action segments with this tag from
     *          the opponent's AP timeline for the current round.
     *          Stored in AbilityFlags.lockedMoveTags (combat engine reads it).
     *
     * Distinct from BLOCK_MOVE_TAG (which was the old name — this replaces it).
     */
    LOCK_MOVE_TAG,

    // ── Sustained costs ───────────────────────────────────────────────────────

    /** Drain CE at the start of each round. Models sustained technique costs. */
    COST_CE_PER_ROUND
}
