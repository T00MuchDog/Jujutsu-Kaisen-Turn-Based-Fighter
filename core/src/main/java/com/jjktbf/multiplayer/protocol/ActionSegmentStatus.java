package com.jjktbf.multiplayer.protocol;

/** Current resolution state of a planned action segment. */
public enum ActionSegmentStatus {
    QUEUED,
    STARTED,
    RESOLVED,
    STUNNED
}
