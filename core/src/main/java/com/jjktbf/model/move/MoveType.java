package com.jjktbf.model.move;

import java.util.Locale;

/** Classifies which kind of character a move was authored for. */
public enum MoveType {
    SORCERER,
    CURSED_SPIRIT,
    SHIKIGAMI;

    /** Missing values are legacy sorcerer moves; unknown values fail loudly. */
    public static MoveType fromStoredValue(String stored) {
        if (stored == null || stored.isBlank()) return SORCERER;
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unknown move type '" + stored + "' (expected one of "
                    + java.util.Arrays.toString(values()) + ")");
        }
    }
}
