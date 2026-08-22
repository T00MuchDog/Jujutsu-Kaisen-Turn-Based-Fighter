package com.jjktbf.model.weapon;

import com.jjktbf.model.move.MoveTag;

import java.util.Locale;

/**
 * The kinds of wieldable weapons a character may equip.
 *
 * <p>Each weapon type pairs 1:1 with a weapon-type {@link MoveTag} (see
 * {@link MoveTag#WEAPON_TAGS}). A move carrying one or more weapon tags can
 * only be learned by a character with at least one corresponding weapon
 * equipped, either as a base weapon or through a cursed tool.
 */
public enum WeaponType {

    KATANA("Katana", MoveTag.KATANA),
    BOW("Bow", MoveTag.BOW),
    GREAT_AXE("Great Axe", MoveTag.GREAT_AXE),
    POLEARM("Polearm", MoveTag.POLEARM),
    STAFF("Staff", MoveTag.STAFF);

    private final String displayName;
    private final MoveTag moveTag;

    WeaponType(String displayName, MoveTag moveTag) {
        this.displayName = displayName;
        this.moveTag = moveTag;
    }

    /** Human-readable name used by editors and error messages. */
    public String displayName() {
        return displayName;
    }

    /** The move tag gating moves of this weapon type. */
    public MoveTag moveTag() {
        return moveTag;
    }

    /** The weapon type paired with the given weapon move tag. */
    public static WeaponType fromMoveTag(MoveTag tag) {
        for (WeaponType type : values()) {
            if (type.moveTag == tag) return type;
        }
        throw new IllegalArgumentException("Not a weapon move tag: " + tag);
    }

    /**
     * Parse a stored enum name, case-insensitively. Blank values resolve to
     * {@code null} (no weapon); unknown values fail loudly.
     */
    public static WeaponType fromStoredValue(String stored) {
        if (stored == null || stored.isBlank()) return null;
        return valueOf(stored.trim().toUpperCase(Locale.ROOT));
    }
}
