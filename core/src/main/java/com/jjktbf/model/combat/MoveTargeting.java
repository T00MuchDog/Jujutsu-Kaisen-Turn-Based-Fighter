package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveTag;

/**
 * Derived targeting classification for a move, computed from its persisted tags.
 *
 * <p>The persisted {@link MoveTag}s remain the source of truth; this enum is the
 * runtime-friendly projection of those tags into a targeting decision. It drives
 * whether the planning UI shows a target menu and how the resolver expands a
 * move's effect set.
 *
 * <ul>
 *   <li>{@link #NONE} — no target selection needed (defensive, self-only utility,
 *       summon-only moves).</li>
 *   <li>{@link #SINGLE_ENEMY} — one hostile combatant must be selected.</li>
 *   <li>{@link #ALL_ENEMIES} — {@link MoveTag#AOE} without friendly fire: hits
 *       every active enemy.</li>
 *   <li>{@link #ALL_OTHERS} — {@link MoveTag#AOE} with friendly fire: hits every
 *       active combatant except the caster.</li>
 * </ul>
 *
 * <p>Note: {@link MoveTag#FRIENDLY_FIRE} is invalid without {@link MoveTag#AOE}.
 */
public enum MoveTargeting {
    NONE,
    SINGLE_ENEMY,
    ALL_ENEMIES,
    ALL_OTHERS;

    /**
     * Project a move's persisted tags into a targeting classification.
     * No {@link MoveTag#AOE} tag means single target (or NONE for non-hostile moves).
     */
    public static MoveTargeting forMove(Move move) {
        if (move == null) return NONE;
        if (!move.isHostile()) return NONE;
        if (!move.isAoe()) return SINGLE_ENEMY;
        return move.hasTag(MoveTag.FRIENDLY_FIRE.name()) ? ALL_OTHERS : ALL_ENEMIES;
    }

    /** True when this targeting requires an explicit selected target. */
    public boolean requiresSelectedTarget() {
        return this == SINGLE_ENEMY;
    }

    /** True when this targeting fans out over multiple combatants. */
    public boolean isAreaOfEffect() {
        return this == ALL_ENEMIES || this == ALL_OTHERS;
    }
}
