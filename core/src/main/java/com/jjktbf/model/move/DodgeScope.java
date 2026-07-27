package com.jjktbf.model.move;

/**
 * Which attack ranges a {@link DefenseType#DODGE} move reacts to.
 *
 * <p>Stored on {@link MoveData#getDodgeScope()} as the enum name string.</p>
 */
public enum DodgeScope {
    /** React only to {@link MoveTag#MELEE} attacks. */
    MELEE,
    /** React only to {@link MoveTag#RANGED} attacks. */
    RANGED,
    /** React to attacks of any range. */
    BOTH
}
