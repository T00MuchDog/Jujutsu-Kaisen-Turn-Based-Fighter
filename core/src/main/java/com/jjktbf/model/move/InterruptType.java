package com.jjktbf.model.move;

/**
 * Describes whether and how a move can interrupt the opponent's queued actions.
 */
public enum InterruptType {

    /** No interrupt capability. */
    NONE,

    /**
     * On unleash, removes the move block that the action counter is currently
     * occupying on the opponent's timeline, replacing it with nothing (empty AP).
     * The removed block's CE cost has already been paid (CE is drained when a block begins).
     */
    KNOCK_CURRENT_BLOCK,

    /**
     * On unleash, removes the NEXT move block in the opponent's queue
     * (the block immediately after the current position on their timeline).
     */
    KNOCK_NEXT_BLOCK
}
