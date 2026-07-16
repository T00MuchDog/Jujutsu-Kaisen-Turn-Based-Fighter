package com.jjktbf.server;

import com.jjktbf.server.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Production bootstrap for the runnable multiplayer server. */
public final class ServerMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
    }

    public static void main(String[] args) {
        MultiplayerServer server = new MultiplayerServer(ServerConfig.load());
        Thread shutdownHook = new Thread(() -> {
            try {
                server.close();
            } catch (RuntimeException exception) {
                LOGGER.error("Multiplayer server shutdown failed", exception);
            }
        }, "jjktbf-server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            server.start();
            LOGGER.info("ServerMain ready on port={}", server.port());
        } catch (RuntimeException exception) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            try {
                server.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }
}
