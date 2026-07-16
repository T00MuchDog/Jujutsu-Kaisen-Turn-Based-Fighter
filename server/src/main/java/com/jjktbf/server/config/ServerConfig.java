package com.jjktbf.server.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Immutable server configuration loaded from environment variables. */
public record ServerConfig(
    int serverPort,
    String databaseUrl,
    String databaseUsername,
    String databasePassword,
    String authTokenSecret,
    int challengeExpiryMinutes,
    int disconnectTimeoutSeconds,
    int maxOpenChallenges
) {
    public static final int DEFAULT_SERVER_PORT = 7070;
    public static final String DEFAULT_DATABASE_URL =
        "jdbc:h2:file:./server-data/jjktbf;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE";
    /**
     * Stable local-development fallback only. Production deployments must set
     * AUTH_TOKEN_SECRET to a private, randomly generated value.
     */
    public static final String DEVELOPMENT_ONLY_DEFAULT_AUTH_TOKEN_SECRET =
        "jjktbf-development-only-auth-secret-not-for-production";
    public static final int DEFAULT_CHALLENGE_EXPIRY_MINUTES = 15;
    public static final int DEFAULT_DISCONNECT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_OPEN_CHALLENGES = 3;

    public ServerConfig {
        if (serverPort < 0 || serverPort > 65_535) {
            throw new IllegalArgumentException("serverPort must be between 0 and 65535");
        }
        databaseUrl = requireText(databaseUrl, "databaseUrl");
        databaseUsername = Objects.requireNonNullElse(databaseUsername, "");
        databasePassword = Objects.requireNonNullElse(databasePassword, "");
        authTokenSecret = requireText(authTokenSecret, "authTokenSecret");
        if (authTokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("authTokenSecret must contain at least 32 bytes");
        }
        if (databaseUrl.startsWith("jdbc:postgresql:")
            && DEVELOPMENT_ONLY_DEFAULT_AUTH_TOKEN_SECRET.equals(authTokenSecret)) {
            throw new IllegalArgumentException(
                "AUTH_TOKEN_SECRET must be configured for PostgreSQL deployments");
        }
        if (challengeExpiryMinutes < 1) {
            throw new IllegalArgumentException("challengeExpiryMinutes must be positive");
        }
        if (disconnectTimeoutSeconds < 1) {
            throw new IllegalArgumentException("disconnectTimeoutSeconds must be positive");
        }
        if (maxOpenChallenges < 1) {
            throw new IllegalArgumentException("maxOpenChallenges must be positive");
        }
    }

    public static ServerConfig load() {
        return fromEnvironment(System.getenv());
    }

    public static ServerConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        DatabaseSettings database = databaseSettings(valueOrDefault(
            environment, "DATABASE_URL", DEFAULT_DATABASE_URL));
        return new ServerConfig(
            serverPort(environment.get("SERVER_PORT")),
            database.url(),
            valueOrDefault(
                environment,
                "DATABASE_USERNAME",
                database.username().isBlank()
                    ? defaultUsername(database.url()) : database.username()
            ),
            valueOrDefault(environment, "DATABASE_PASSWORD", database.password()),
            authTokenSecret(environment),
            positiveInt(environment.get("CHALLENGE_EXPIRY_MINUTES"),
                DEFAULT_CHALLENGE_EXPIRY_MINUTES, Integer.MAX_VALUE),
            positiveInt(environment.get("DISCONNECT_TIMEOUT_SECONDS"),
                DEFAULT_DISCONNECT_TIMEOUT_SECONDS, Integer.MAX_VALUE),
            positiveInt(environment.get("MAX_OPEN_CHALLENGES"),
                DEFAULT_MAX_OPEN_CHALLENGES, Integer.MAX_VALUE)
        );
    }

    private static DatabaseSettings databaseSettings(String value) {
        String url = value.trim();
        if (!url.startsWith("postgres://") && !url.startsWith("postgresql://")) {
            return new DatabaseSettings(url, "", "");
        }

        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("DATABASE_URL is not a valid PostgreSQL URI");
            }
            String authority = uri.getRawAuthority();
            int userInfoSeparator = authority.lastIndexOf('@');
            if (userInfoSeparator >= 0) {
                authority = authority.substring(userInfoSeparator + 1);
            }
            StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(authority)
                .append(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (uri.getRawQuery() != null) {
                jdbcUrl.append('?').append(uri.getRawQuery());
            }

            String username = "";
            String password = "";
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                username = separator < 0 ? userInfo : userInfo.substring(0, separator);
                password = separator < 0 ? "" : userInfo.substring(separator + 1);
            }
            return new DatabaseSettings(jdbcUrl.toString(), username, password);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid URI");
        }
    }

    private static String defaultUsername(String databaseUrl) {
        return databaseUrl.startsWith("jdbc:h2:") ? "sa" : "";
    }

    private static String authTokenSecret(Map<String, String> environment) {
        String configured = environment.get("AUTH_TOKEN_SECRET");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return DEVELOPMENT_ONLY_DEFAULT_AUTH_TOKEN_SECRET;
    }

    private static int serverPort(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_SERVER_PORT;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            if (parsed < 0 || parsed > 65_535) {
                throw new IllegalArgumentException("SERVER_PORT must be between 0 and 65535");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SERVER_PORT must be an integer", exception);
        }
    }

    private static int positiveInt(String rawValue, int defaultValue, int maximum) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            if (parsed < 1 || parsed > maximum) {
                throw new IllegalArgumentException("Configuration value must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Configuration value must be an integer", exception);
        }
    }

    private static String valueOrDefault(
        Map<String, String> environment,
        String key,
        String defaultValue
    ) {
        String value = environment.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record DatabaseSettings(String url, String username, String password) {
    }
}
