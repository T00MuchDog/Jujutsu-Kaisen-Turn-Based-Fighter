package com.jjktbf.model.move;

/**
 * Authoritative area-of-effect targeting shape for a move that carries the
 * {@link MoveTag#AOE} tag.
 *
 * <p>This is the authored sub-field that distinguishes <em>what kind</em> of
 * area a move covers, sitting one level below the {@link MoveTag#AOE} tag itself
 * (which merely marks a move as non-single-target). It is the single source of
 * truth for how an AOE move fans out at fire time:
 *
 * <ul>
 *   <li>{@link #MULTIPLE} — hits a fixed number of hostile combatants (the count
 *       is stored alongside this value as {@code aoeTargetCount} on the move).</li>
 *   <li>{@link #ALL_ENEMIES} — hits every active enemy of the caster.</li>
 *   <li>{@link #ALL_OTHERS} — hits every active combatant except the caster,
 *       <em>including</em> the caster's own allies (friendly fire).</li>
 * </ul>
 *
 * <p>For back-compat, a move with the {@link MoveTag#AOE} tag but no authored
 * {@code AoeType} defaults to {@link #ALL_ENEMIES}; a move that also carries
 * the legacy {@link MoveTag#FRIENDLY_FIRE} tag is migrated to {@link #ALL_OTHERS}.
 * {@link MoveTag#FRIENDLY_FIRE} is retained on the tag set for back-compat but
 * is no longer the authoring surface — this enum is.
 */
public enum AoeType {
    /** Hits a fixed number of targets (see {@code aoeTargetCount}). */
    MULTIPLE("Multiple Targets"),
    /** Hits every active enemy of the caster. */
    ALL_ENEMIES("All Enemies"),
    /** Hits every active combatant except the caster (allies included). */
    ALL_OTHERS("All Others");

    private final String displayName;

    AoeType(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for editor dropdowns. */
    public String displayName() {
        return displayName;
    }

    /**
     * Parse a stored enum name tolerantly. {@code null}/blank resolves to
     * {@code null} so callers can apply the AOE-tag default themselves.
     */
    public static AoeType fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return AoeType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
