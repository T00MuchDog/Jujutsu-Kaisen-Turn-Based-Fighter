package com.jjktbf.model.move;

/**
 * Describes whether and how a move can interrupt the opponent's queued actions.
 */
public enum InterruptType {

    /** No interrupt capability. */
    NONE,

    /**
     * On unleash, removes the action segment that the action counter is currently
     * occupying on the opponent's timeline, replacing it with nothing (empty AP).
     * The removed segment's CE cost has already been paid (CE is drained when a segment begins).
     */
    KNOCK_CURRENT_SEGMENT,

    /**
     * On unleash, removes the NEXT action segment in the opponent's queue
     * (the segment immediately after the current position on their timeline).
     */
    KNOCK_NEXT_SEGMENT
}
