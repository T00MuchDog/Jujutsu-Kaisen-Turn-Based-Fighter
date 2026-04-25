package com.jjktbf.model.character;

/**
 * Broad classification of a character.
 * Determines which move categories are accessible and certain passive rule interactions.
 */
public enum CharacterType {

    /**
     * A jujutsu sorcerer with an innate cursed technique.
     * Has access to INNATE_TECHNIQUE tagged moves (gated by requiredTechniqueId).
     * CursedTechniqueMastery stat is meaningful.
     */
    SORCERER_INNATE,

    /**
     * A jujutsu sorcerer without an innate technique.
     * Relies on non-innate techniques, barriers, RCT, CE reinforcement.
     * CursedTechniqueMastery stat is vestigial (remains at baseline).
     */
    SORCERER_NON_INNATE,

    /**
     * A cursed spirit. May have an innate technique.
     * Generally higher raw stats, lower jujutsu versatility.
     */
    CURSED_SPIRIT,

    /**
     * A human with no cursed energy capability.
     * Can only use PHYSICAL tagged moves and non-CE UTILITY moves.
     */
    HUMAN
}
