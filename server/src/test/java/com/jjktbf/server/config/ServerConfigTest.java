package com.jjktbf.server.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @Test
    void defaultsAreSafeForLocalEmbeddedDevelopment() {
        ServerConfig config = ServerConfig.fromEnvironment(Map.of());

        assertEquals(ServerConfig.DEFAULT_SERVER_PORT, config.serverPort());
        assertTrue(config.databaseUrl().startsWith("jdbc:h2:file:"));
        assertTrue(config.databaseUrl().contains("MODE=PostgreSQL"));
        assertEquals(ServerConfig.DEVELOPMENT_ONLY_DEFAULT_AUTH_TOKEN_SECRET,
            config.authTokenSecret());
        assertEquals(3, config.maxOpenChallenges());
    }

    @Test
    void hostedPostgresUrlsProvideJdbcUrlAndDecodedCredentials() {
        ServerConfig config = ServerConfig.fromEnvironment(Map.of(
            "DATABASE_URL",
            "postgresql://db-user:p%40ss@db.example:5433/jjktbf?sslmode=require",
            "AUTH_TOKEN_SECRET", "configured-production-secret-with-32-bytes-minimum",
            "MAX_OPEN_CHALLENGES", " 7 "
        ));

        assertEquals(
            "jdbc:postgresql://db.example:5433/jjktbf?sslmode=require",
            config.databaseUrl());
        assertEquals("db-user", config.databaseUsername());
        assertEquals("p@ss", config.databasePassword());
        assertEquals(7, config.maxOpenChallenges());
    }

    @Test
    void malformedExplicitConfigurationFailsFast() {
        assertThrows(IllegalArgumentException.class, () ->
            ServerConfig.fromEnvironment(Map.of("SERVER_PORT", "not-a-number")));
        assertThrows(IllegalArgumentException.class, () ->
            ServerConfig.fromEnvironment(Map.of("CHALLENGE_EXPIRY_MINUTES", "-2")));
        assertThrows(IllegalArgumentException.class, () ->
            ServerConfig.fromEnvironment(Map.of("AUTH_TOKEN_SECRET", "too-short")));
        assertThrows(IllegalArgumentException.class, () ->
            ServerConfig.fromEnvironment(Map.of(
                "DATABASE_URL", "postgresql://db.example/jjktbf")));
    }

    @Test
    void developmentSecretAndEphemeralPortAreStableAcrossLoads() {
        ServerConfig first = ServerConfig.fromEnvironment(Map.of("SERVER_PORT", "0"));
        ServerConfig second = ServerConfig.fromEnvironment(Map.of("SERVER_PORT", "0"));

        assertEquals(0, first.serverPort());
        assertEquals(first.authTokenSecret(), second.authTokenSecret());
    }
}
