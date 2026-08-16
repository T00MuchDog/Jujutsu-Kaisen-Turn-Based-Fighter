package com.jjktbf.model.move;

/**
 * When a Defensive+Attack hybrid move launches its attack portion.
 *
 * <p>A hybrid lives on the defensive timeline (the DEFENSIVE tag wins over
 * ATTACK) and grants its active-defense window as usual; the launch mode
 * decides when the attack itself fires:</p>
 *
 * <ul>
 *   <li>{@link #ON_FIRE} — the attack launches at the move's own firing tick,
 *       immediately after the defence is granted.</li>
 *   <li>{@link #ON_DEFENCE} — the attack launches when this move's defence
 *       successfully resolves an incoming attack (block, dodge, or parry),
 *       targeting the attacker it just defended against.</li>
 * </ul>
 */
public enum AttackLaunchMode {
    ON_FIRE("On fire"),
    ON_DEFENCE("On defence");

    private final String displayName;

    AttackLaunchMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Tolerant reader: blank or unknown values resolve to null (no launch authored). */
    public static AttackLaunchMode fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknownValue) {
            return null;
        }
    }
}
