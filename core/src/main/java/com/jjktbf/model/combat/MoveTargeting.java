package com.jjktbf.model.combat;

import com.jjktbf.model.move.AoeType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveTag;

/**
 * Derived targeting classification for a move, computed from its persisted tags
 * and authoritative {@link AoeType}.
 *
 * <p>The persisted {@link MoveTag}s and {@link AoeType} remain the source of
 * truth; this enum is the runtime-friendly projection of that state into a
 * targeting decision. It drives whether the planning UI shows a target menu and
 * how the resolver expands a move's effect set.
 *
 * <ul>
 *   <li>{@link #NONE} — no target selection needed (defensive, self-only utility,
 *       summon-only moves).</li>
 *   <li>{@link #SINGLE_ENEMY} — one hostile combatant must be selected.</li>
     *   <li>{@link #MULTIPLE_ENEMIES} — {@link AoeType#MULTIPLE}: the player selects
     *       between one and the move's configured maximum active enemies.</li>
 *   <li>{@link #ALL_ENEMIES} — {@link AoeType#ALL_ENEMIES}: hits every active
 *       enemy of the caster.</li>
 *   <li>{@link #ALL_OTHERS} — {@link AoeType#ALL_OTHERS}: hits every active
 *       combatant except the caster, including the caster's own allies.</li>
 * </ul>
 *
 * <p>Note: {@link MoveTag#FRIENDLY_FIRE} is retained on the tag set for back-compat
 * but is no longer the authoring surface — {@link AoeType} is authoritative.
 */
public enum MoveTargeting {
    NONE,
    SINGLE_ENEMY,
    MULTIPLE_ENEMIES,
    ALL_ENEMIES,
    ALL_OTHERS;

    /**
     * Project a move's persisted state into a targeting classification.
     * No {@link MoveTag#AOE} tag means single target (or NONE for non-hostile moves).
     * For AOE moves, the {@link AoeType} sub-field decides the fan-out shape.
     */
    public static MoveTargeting forMove(Move move) {
        if (move == null) return NONE;
        if (!move.isHostile()) return NONE;
        if (!move.isAoe()) return SINGLE_ENEMY;
        AoeType shape = move.getAoeType();
        if (shape == null) return ALL_ENEMIES; // defensive default; resolveAoeType normally guarantees non-null
        return switch (shape) {
            case MULTIPLE  -> MULTIPLE_ENEMIES;
            case ALL_OTHERS -> ALL_OTHERS;
            case ALL_ENEMIES -> ALL_ENEMIES;
        };
    }

    /** True when this targeting requires an explicit selected target. */
    public boolean requiresSelectedTarget() {
        return this == SINGLE_ENEMY;
    }

    /** True when the player must explicitly select one or more combatants. */
    public boolean requiresSelectedTargets() {
        return this == SINGLE_ENEMY || this == MULTIPLE_ENEMIES;
    }

    /** True when this targeting fans out over multiple combatants. */
    public boolean isAreaOfEffect() {
        return this == MULTIPLE_ENEMIES || this == ALL_ENEMIES || this == ALL_OTHERS;
    }
}
