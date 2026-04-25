package com.jjktbf.model.move;

/**
 * Describes the defensive behavior of a DEFENSIVE-tagged move.
 */
public enum DefenseType {

    /** No defensive behavior — this is not a defensive move. */
    NONE,

    /**
     * Temporarily raises the Defense stat for a portion of or the full round.
     * The enhancement applies from the move's unleash point onward for the
     * specified duration (in AP ticks). After expiry, Defense returns to base.
     */
    STAT_BUFF,

    /**
     * Full damage block.
     * If the action counter is still on this move's block when an incoming
     * attack is unleashed (i.e. the attack's unleash point has not yet been
     * reached), all damage from that attack is negated.
     *
     * This creates the mind-game layer: if the attacker's move fires before
     * this block resolves, the block does nothing.
     */
    FULL_BLOCK,

    /**
     * Partial block.
     * If active when an attack fires, incoming damage is halved BEFORE
     * the normal defense calculation is applied.
     * Less reliable than FULL_BLOCK but costs less AP and is still useful
     * even if the attacker fires first.
     */
    PARTIAL_BLOCK
}
