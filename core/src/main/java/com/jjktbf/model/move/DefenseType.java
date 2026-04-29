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
     * Active damage-blocking move.
     * Has duration (AP ticks), affected tags, and damage reduction %.
     * Incoming attacks are reduced if they unleash within the block's duration window.
     * The block activates at its unleash point and lasts for blockDuration ticks.
     * If blockDuration = 0, uses the move's apCost as the duration.
     * If blockDuration = -1, lasts until end of round.
     */
    BLOCK
}
