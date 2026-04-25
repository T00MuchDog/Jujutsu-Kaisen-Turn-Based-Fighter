package com.jjktbf.model.move;

/**
 * All possible tags a move can carry.
 *
 * Tags serve three purposes:
 *  1. Determine the Power formula used during damage calculation.
 *  2. Gate which characters can learn/use the move.
 *  3. Determine whether a Black Flash can proc (requires PHYSICAL + any CE-bearing tag).
 *
 * Tag combinations and their Power formulae:
 *
 *  PHYSICAL only
 *      Power = (Strength × CombatAbility)  [4:1 ratio]
 *
 *  CURSED_ENERGY only  (no standalone CE-only moves; CE is always paired)
 *      Power = (CE_Output × CE_Reserves × CE_Efficiency)  [3:2:1 ratio]
 *
 *  INNATE_TECHNIQUE  (implies CURSED_ENERGY tag for BF purposes)
 *      Power = CursedEnergy_component × CursedTechniqueMastery  [50:50]
 *
 *  NON_INNATE_TECHNIQUE  (implies CURSED_ENERGY tag for BF purposes)
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
 * Black Flash eligibility: move must contain PHYSICAL and at least one of
 *   {CURSED_ENERGY, INNATE_TECHNIQUE, NON_INNATE_TECHNIQUE}.
 */
public enum MoveTag {

    /** Pure physical strike — fists, weapons, body. Governed by Strength + CombatAbility. */
    PHYSICAL,

    /**
     * Cursed energy infused move (not technique-specific).
     * Governed by CE_Output, CE_Reserves, CE_Efficiency.
     * Note: no move carries CURSED_ENERGY as its sole tag; it always pairs with others.
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
    DEFENSIVE
}
