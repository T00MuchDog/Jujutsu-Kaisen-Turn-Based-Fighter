package com.jjktbf.model.character;

import com.jjktbf.model.move.MoveType;

import java.util.Locale;

/**
 * Broad classification of a character definition.
 *
 * The type is a property of the canonical {@link com.jjktbf.model.character.Character}
 * definition, distinct from the runtime role ({@code FIGHTER}/{@code SUMMON}) a
 * combatant plays inside a battle. A {@code SHIKIGAMI} definition, for example,
 * becomes a {@code SUMMON} combatant when summoned but is still a complete
 * character with stats, moves, and abilities.
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
     * Learns sorcerer moves (gated by stats and technique possession).
     */
    SORCERER,

    /** A cursed spirit. Learns only cursed-spirit moves. */
    CURSED_SPIRIT,

    /** A cursed corpse. Learns both sorcerer and shikigami moves. */
    CURSED_CORPSE,

    /**
     * A shikigami — a summoned combatant (e.g. the Divine Dogs, Mahoraga).
     * Functions as a complete character definition (stats, moves, abilities) so
     * it can be summoned into battle as a full combatant. Defaults to not being
     * directly selectable from the fighter roster, though a definition may be
     * explicitly marked selectable in the editor.
     */
    SHIKIGAMI;

    /** Whether this character class may learn a move of the given authored type. */
    public boolean canLearn(MoveType moveType) {
        if (moveType == null) return false;
        return switch (this) {
            case SORCERER -> moveType == MoveType.SORCERER;
            case CURSED_SPIRIT -> moveType == MoveType.CURSED_SPIRIT;
            case CURSED_CORPSE, SHIKIGAMI -> moveType == MoveType.SORCERER
                || moveType == MoveType.SHIKIGAMI;
        };
    }

    /**
     * Parse a stored type string. A missing or blank value resolves to
     * {@link #SORCERER} (the legacy default). An unknown non-blank value fails
     * loudly so a typo cannot silently downgrade a definition.
     */
    public static CharacterType fromStoredValue(String stored) {
        if (stored == null || stored.isBlank()) {
            return SORCERER;
        }
        try {
            return CharacterType.valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Unknown character type '" + stored + "' (expected one of "
                    + java.util.Arrays.toString(values()) + ")");
        }
    }
}
