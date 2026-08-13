package com.jjktbf.model.move;

import java.util.Set;

/**
 * All possible tags a move can carry.
 *
 * Tags serve three purposes:
 *  1. Determine the Power formula used during damage calculation.
 *  2. Gate which characters can learn/use the move.
 *  3. Determine whether a Black Flash can proc (requires PHYSICAL + CURSED_ENERGY
 *     with no technique tag).
 *
 * Tag combinations and their Power formulae:
 *
 *  PHYSICAL only
 *      Power = (Strength × CombatAbility)  [4:1 ratio]
 *
 *  CURSED_ENERGY only  (raw CE attack — dedicated CURSED_ENERGY category)
 *      Power = (CE_Output × CE_Reserves × CE_Efficiency)  [3:2:1 ratio]
 *
 *  INNATE_TECHNIQUE  (uses cursed energy)
 *      Power = CursedEnergy_component × CursedTechniqueMastery  [50:50]
 *
 *  NON_INNATE_TECHNIQUE  (uses cursed energy)
 *      Power = CursedEnergy_component × JujutsuSkill  [50:50]
 *
 *  PHYSICAL + CURSED_ENERGY
 *      Power = 3:1  CursedEnergy : Physical
 *
 *  PHYSICAL + INNATE_TECHNIQUE
 *      Power = 4:1  InnateT : Physical
 *
 *  PHYSICAL + NON_INNATE_TECHNIQUE
 *      Power = 3:1  NonInnateT : Physical
 *
 *  INNATE_TECHNIQUE + NON_INNATE_TECHNIQUE
 *      Power = 3:2  InnateT : NonInnateT
 *
 *  PHYSICAL + INNATE_TECHNIQUE + NON_INNATE_TECHNIQUE
 *      Power = 1:3:2  Physical : InnateT : NonInnateT
 *
 * Black Flash eligibility: a hit component must contain PHYSICAL and
 * CURSED_ENERGY, and must not contain INNATE_TECHNIQUE or
 * NON_INNATE_TECHNIQUE.
 */
public enum MoveTag {

    /** Pure physical strike — fists, weapons, body. Governed by Strength + CombatAbility. */
    PHYSICAL,

    /**
     * Cursed energy infused move (not technique-specific).
     * Governed by CE_Output, CE_Reserves, CE_Efficiency.
     * May appear alone as a raw CE attack (the dedicated CURSED_ENERGY
     * {@link MoveCategory}), or paired with PHYSICAL / technique tags.
     */
    CURSED_ENERGY,

    /**
     * Move tied to a specific innate cursed technique (e.g. "Blood Manipulation", "Shrine").
     * Implies cursed energy usage. Restricted to characters possessing that technique.
     * Governed by CE component + CursedTechniqueMastery.
     */
    INNATE_TECHNIQUE,

    /**
     * Move from a learned, non-innate technique (barriers, RCT, cursed tool amplification, etc.).
     * Implies cursed energy usage. Governed by CE component + JujutsuSkill.
     */
    NON_INNATE_TECHNIQUE,

    /**
     * General attack tag — marks the move as an offensive action.
     * Applied in addition to PHYSICAL / CURSED_ENERGY / INNATE_TECHNIQUE etc.
     * Used for filtering, AI logic, and future mechanics.
     */
    ATTACK,

    /** Utility / status move — does not deal direct damage. */
    UTILITY,

    /** Defensive move that reduces or blocks incoming damage. */
    DEFENSIVE,

    /**
     * Melee range modifier — marks an attack as a close-quarters strike. Only
     * meaningful on {@link #ATTACK} moves: defensive and utility moves ignore it.
     *
     * A modifier tag like {@link #ATTACK}: it does not affect
     * the Power formula, is not part of any {@link MoveCategory}'s tag set, and
     * does not change Black Flash eligibility. It is NOT backed by a dedicated
     * flag on {@link Move} — it lives purely in the tag set.
     */
    MELEE,

    /**
     * Ranged range modifier — marks an attack as a distance strike (projectile,
     * beam, thrown weapon, etc.). Only meaningful on {@link #ATTACK} moves.
     *
     * A modifier tag like {@link #ATTACK} and {@link #MELEE}: it does not affect
     * the Power formula, is not part of any {@link MoveCategory}'s tag set, and
     * does not change Black Flash eligibility. It is NOT backed by a dedicated
     * flag on {@link Move} — it lives purely in the tag set.
     */
    RANGED,

    /**
     * Area-of-effect targeting modifier. Moves without this tag are single-target.
     * This does not affect range, damage calculation, or move category.
     */
    AOE,

    /**
     * Friendly-fire modifier. Only meaningful together with {@link #AOE}: an
     * {@code AOE + FRIENDLY_FIRE} move hits every active combatant except the
     * caster (allies and enemies alike). {@code FRIENDLY_FIRE} without
     * {@code AOE} is invalid and rejected at authoring/validation time.
     *
     * <p>Like {@link #AOE}, this is a pure tag-set modifier — it does not affect
     * the Power formula, range, or move category.
     */
    FRIENDLY_FIRE,

    /** Sword or sword-like weapon modifier used by move-scoped abilities. */
    SWORD,

    /**
     * Guard break modifier — a successful hit from this move ignores the defender's
     * blocking defensive moves (BLOCK). Dodges and parries are unaffected; only
     * blocks are bypassed. A guard-break attack is still negated by a parry but
     * does not get staggered by one.
     *
     * A modifier tag like {@link #ATTACK}: it does not affect the
     * Power formula, is not part of any {@link com.jjktbf.model.move.MoveCategory}'s
     * tag set, and does not change Black Flash eligibility. It is stored as a dedicated
     * flag on {@link com.jjktbf.model.move.Move} (see {@link Move#isGuardBreak()}),
     * not derived from the category.
     */
    GUARD_BREAK,

    /**
     * Heavy modifier — an action segment carrying this move cannot be cancelled by
     * a stun-current-action effect. Interrupts and ongoing statuses are unaffected.
     *
     * A modifier tag like {@link #ATTACK} and {@link #GUARD_BREAK}:
     * it does not affect the Power formula, is not part of any
     * {@link com.jjktbf.model.move.MoveCategory}'s tag set, and does not change
     * Black Flash eligibility. It is stored as a dedicated flag on
     * {@link com.jjktbf.model.move.Move} (see {@link Move#isHeavy()}), not derived
     * from the category.
     */
    HEAVY,

    /**
     * Intangible modifier — an attack carrying this tag cannot be parried and
     * ignores all block reduction. Dodge, accuracy, ordinary Defense, and other
     * hit-negation mechanics are unaffected.
     *
     * <p>This is a pure tag-set modifier with no dedicated flag on {@link Move}.
     * It does not affect the Power formula, move category, or Black Flash
     * eligibility.
     */
    INTANGIBLE;

    // -------------------------------------------------------------------------
    // Canonical groupings
    // -------------------------------------------------------------------------

    /** Damage-nature tags that select the Power formula / MoveCategory. */
    public static final Set<MoveTag> TYPE_TAGS = Set.of(
        PHYSICAL, CURSED_ENERGY, INNATE_TECHNIQUE, NON_INNATE_TECHNIQUE);

    /** Range tags — only meaningful on ATTACK moves. */
    public static final Set<MoveTag> RANGE_TAGS = Set.of(MELEE, RANGED);
}
