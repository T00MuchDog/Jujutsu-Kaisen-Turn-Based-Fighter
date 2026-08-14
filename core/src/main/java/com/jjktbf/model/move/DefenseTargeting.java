package com.jjktbf.model.move;

/**
 * Authoritative targeting shape for a {@link DefenseType#BLOCK BLOCK},
 * {@link DefenseType#PARRY PARRY}, or {@link DefenseType#DODGE DODGE} move:
 * <em>whose</em> timeline the active-defense window is conferred to at fire time.
 *
 * <p>This is the defensive analogue of attack AOE targeting ({@link AoeType} +
 * {@link com.jjktbf.model.combat.MoveTargeting}). It is the single source of
 * truth for how a defensive move fans out at fire time:
 *
 * <ul>
 *   <li>{@link #SELF} — protects the caster (the historical behaviour; the
 *       defense segment stays on the caster's own timeline). This is the
 *       default for every defensive move.</li>
 *   <li>{@link #SINGLE_ALLY} — protects one explicitly selected ally.</li>
 *   <li>{@link #MULTIPLE_ALLIES} — protects up to {@code defenseTargetCount}
 *       explicitly selected allies.</li>
 *   <li>{@link #ALL_ALLIES_EXCEPT_SELF} — protects every active ally of the
 *       caster (no selection needed).</li>
 *   <li>{@link #ALL_ALLIES_INCLUDING_SELF} — protects the caster and every
 *       active ally (no selection needed).</li>
 * </ul>
 *
 * <p>At fire time the resolver grants a fired copy of the defense segment to
 * each beneficiary's timeline; the original on the caster's timeline is then
 * marked "transferred" so it no longer protects the caster (unless the caster
 * is itself a beneficiary).
 */
public enum DefenseTargeting {
    /** Protects the caster only (default; segment stays on the caster's timeline). */
    SELF("Self"),
    /** Protects one explicitly selected ally. */
    SINGLE_ALLY("Single Ally"),
    /** Protects up to {@code defenseTargetCount} explicitly selected allies. */
    MULTIPLE_ALLIES("Multiple Allies"),
    /** Protects every active ally of the caster (auto, no target selection). */
    ALL_ALLIES_EXCEPT_SELF("All Allies Except Self"),
    /** Protects the caster and every active ally (auto, no target selection). */
    ALL_ALLIES_INCLUDING_SELF("All Allies Including Self");

    private final String displayName;

    DefenseTargeting(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for editor dropdowns. */
    public String displayName() {
        return displayName;
    }

    /**
     * Parse a stored enum name tolerantly. {@code null}/blank resolves to
     * {@link #SELF} (the safe default, preserving legacy defensive moves).
     */
    public static DefenseTargeting fromName(String name) {
        if (name == null || name.isBlank()) return SELF;
        try {
            return DefenseTargeting.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return SELF;
        }
    }

    /**
     * Project a move's persisted state into a defensive targeting decision.
     * Non-defensive moves, or moves with no authored targeting, resolve to
     * {@link #SELF}.
     */
    public static DefenseTargeting forMove(Move move) {
        if (move == null) return SELF;
        if (!move.isDefensive()) return SELF;
        return move.getDefenseTargeting();
    }

    /** True when this targeting requires a single explicitly selected ally. */
    public boolean requiresSelectedTarget() {
        return this == SINGLE_ALLY;
    }

    /** True when the player must explicitly select one or more allies. */
    public boolean requiresSelectedTargets() {
        return this == SINGLE_ALLY || this == MULTIPLE_ALLIES;
    }

    /** True when this targeting protects an ally other than (or in addition to) the caster. */
    public boolean includesAlly() {
        return this != SELF;
    }
}
