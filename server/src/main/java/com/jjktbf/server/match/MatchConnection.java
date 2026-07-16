package com.jjktbf.server.match;

import com.jjktbf.multiplayer.protocol.SocketMessage;

/** Transport-neutral connection used by the active match lifecycle. */
public interface MatchConnection {
    String connectionId();

    void send(SocketMessage message);

    boolean isOpen();

    default void close() {
    }
}
