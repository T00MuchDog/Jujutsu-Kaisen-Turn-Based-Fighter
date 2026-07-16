package com.jjktbf.multiplayer.protocol;

/** Explicit WebSocket envelope discriminator. */
public enum MessageType {
    JOIN_MATCH,
    MATCH_JOINED,
    SUBMIT_ACTION,
    MATCH_STATE,
    COMMAND_REJECTED,
    PLAYER_CONNECTED,
    PLAYER_DISCONNECTED,
    MATCH_ENDED,
    PING,
    PONG,
    ERROR
}
