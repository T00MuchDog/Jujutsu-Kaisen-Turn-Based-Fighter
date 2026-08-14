package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.SocketMessage;

import java.util.concurrent.CompletableFuture;

interface MatchSocket extends AutoCloseable {
    CompletableFuture<Void> connect(
        String guestToken,
        String matchId,
        MatchWebSocketClient.Listener listener
    );

    default CompletableFuture<Void> connect(
        String guestToken,
        String matchId,
        String ruleset,
        MatchWebSocketClient.Listener listener
    ) {
        return connect(guestToken, matchId, listener);
    }

    CompletableFuture<Void> send(SocketMessage message);

    void disconnect();

    @Override
    void close();
}
