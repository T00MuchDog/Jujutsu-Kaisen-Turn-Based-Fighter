package com.jjktbf.server.support;

import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.server.auth.GuestAuthService;
import com.jjktbf.server.challenge.ChallengeService;
import com.jjktbf.server.config.ServerConfig;
import com.jjktbf.server.content.ContentCatalog;
import com.jjktbf.server.db.Database;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class ServerTestFixture implements AutoCloseable {
    private final ServerConfig config;
    private final Database database;
    private final ContentCatalog catalog;
    private final MutableClock clock;
    private final GuestAuthService authService;
    private final ChallengeService challengeService;

    public ServerTestFixture() {
        String databaseName = "jjktbf_" + UUID.randomUUID().toString().replace("-", "");
        this.config = new ServerConfig(
            7070,
            "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
            "sa",
            "",
            "unit-test-auth-token-secret-with-sufficient-length",
            5,
            30,
            3
        );
        this.database = new Database(config);
        this.catalog = ContentCatalog.load();
        this.clock = new MutableClock(Instant.parse("2026-07-16T00:00:00Z"));
        this.authService = new GuestAuthService(
            database, config, clock, new SecureRandom());
        this.challengeService = new ChallengeService(
            database, config, catalog, clock, new SecureRandom());
    }

    public ServerConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public ContentCatalog catalog() {
        return catalog;
    }

    public MutableClock clock() {
        return clock;
    }

    public GuestAuthService authService() {
        return authService;
    }

    public ChallengeService challengeService() {
        return challengeService;
    }

    public SessionIdentity createGuest(String displayName) {
        return createGuestResponse(displayName).identity();
    }

    public GuestCreateResponse createGuestResponse(String displayName) {
        return authService.createGuest(Optional.ofNullable(displayName));
    }

    @Override
    public void close() {
        database.close();
    }
}
