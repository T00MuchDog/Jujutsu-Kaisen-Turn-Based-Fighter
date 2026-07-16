package com.jjktbf.server.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.server.match.MatchConnection;
import org.eclipse.jetty.websocket.api.Session;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Jetty-backed match connection with serialized, non-overlapping writes. */
public final class JavalinMatchConnection implements MatchConnection {
    private static final int NORMAL_CLOSE = 1000;

    private final String connectionId;
    private final Session session;
    private final ObjectMapper mapper;
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public JavalinMatchConnection(
        String connectionId,
        Session session,
        ObjectMapper mapper
    ) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.session = Objects.requireNonNull(session, "session");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String connectionId() {
        return connectionId;
    }

    @Override
    public void send(SocketMessage message) {
        Objects.requireNonNull(message, "message");
        String json;
        try {
            json = mapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize socket message", exception);
        }

        synchronized (sendLock) {
            if (!isOpen()) {
                throw new IllegalStateException("Match connection is closed");
            }
            try {
                session.getRemote().sendString(json);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not send socket message", exception);
            }
        }
    }

    @Override
    public boolean isOpen() {
        if (closed.get()) {
            return false;
        }
        try {
            return session.isOpen();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(NORMAL_CLOSE, "Connection closed");
            }
        } catch (RuntimeException ignored) {
            // A concurrent peer close has already made the transport unusable.
        }
    }

    public void markClosed() {
        closed.set(true);
    }
}
