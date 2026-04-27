package com.jjktbf.model.character;

/**
 * Broad classification of a character.
 *
 * Currently only SORCERER exists. Additional types (CURSED_SPIRIT, HUMAN, etc.)
 * will be added in a future pass when those factions are implemented.
 *
 * Whether a sorcerer has an innate technique is determined by the
 * innateTechniqueName field on the character (null = no innate technique),
 * NOT by a separate type variant. This keeps the type enum clean and avoids
 * having to add a new enum value every time technique support changes.
 */
public enum CharacterType {

    /**
     * A jujutsu sorcerer.
     * May or may not have an innate cursed technique — that is governed by
     * the character's innateTechniqueName field.
     * Has full access to all move categories (gated by stats and technique possession).
     */
    SORCERER
}
