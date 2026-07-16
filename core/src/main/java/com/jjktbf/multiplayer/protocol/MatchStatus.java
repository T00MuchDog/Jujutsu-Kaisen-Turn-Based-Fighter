package com.jjktbf.multiplayer.protocol;

/** Authoritative lifecycle of a multiplayer match. */
public enum MatchStatus {
    WAITING,
    ACTIVE,
    OPPONENT_DISCONNECTED,
    ENDED,
    ABANDONED
}
