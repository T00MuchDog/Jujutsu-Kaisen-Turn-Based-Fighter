package com.jjktbf.graphics.multiplayer;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Validated desktop multiplayer endpoints and shared network policy. */
public final class ClientNetworkConfig {
    public static final String HTTP_SYSTEM_PROPERTY = "jjktbf.server.http";
    public static final String WEBSOCKET_SYSTEM_PROPERTY = "jjktbf.server.ws";
    public static final String HTTP_ENVIRONMENT_VARIABLE = "GAME_SERVER_HTTP_URL";
    public static final String WEBSOCKET_ENVIRONMENT_VARIABLE = "GAME_SERVER_WS_URL";

    public static final String DEFAULT_HTTP_URL = "https://play.jjktbf.com";
    public static final String DEFAULT_WEBSOCKET_URL =
            "wss://play.jjktbf.com/ws/matches";

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(8);
    public static final int MAX_RECONNECT_ATTEMPTS = 5;
    public static final int MAX_SOCKET_MESSAGE_BYTES = 1024 * 1024;
    public static final int HTTP_EXECUTOR_THREADS = 4;
    public static final int SOCKET_EXECUTOR_THREADS = 2;
    public static final int EXECUTOR_QUEUE_CAPACITY = 128;

    private final URI httpBaseUri;
    private final URI webSocketUri;

    public ClientNetworkConfig(String httpBaseUrl, String webSocketUrl) {
        this.httpBaseUri = validateAndNormalize(httpBaseUrl, "HTTP base URL", "http", "https");
        this.webSocketUri = validateAndNormalize(
            webSocketUrl, "WebSocket URL", "ws", "wss");
    }

    public ClientNetworkConfig(URI httpBaseUri, URI webSocketUri) {
        this(
            Objects.requireNonNull(httpBaseUri, "httpBaseUri").toString(),
            Objects.requireNonNull(webSocketUri, "webSocketUri").toString()
        );
    }

    /** Loads configuration with system properties taking precedence over environment values. */
    public static ClientNetworkConfig load() {
        return from(System.getProperties(), System.getenv());
    }

    /** Testable configuration loader with the same precedence as {@link #load()}. */
    public static ClientNetworkConfig from(
        Properties systemProperties,
        Map<String, String> environment
    ) {
        Objects.requireNonNull(systemProperties, "systemProperties");
        Objects.requireNonNull(environment, "environment");
        String http = firstNonBlank(
            systemProperties.getProperty(HTTP_SYSTEM_PROPERTY),
            environment.get(HTTP_ENVIRONMENT_VARIABLE),
            DEFAULT_HTTP_URL
        );
        String webSocket = firstNonBlank(
            systemProperties.getProperty(WEBSOCKET_SYSTEM_PROPERTY),
            environment.get(WEBSOCKET_ENVIRONMENT_VARIABLE),
            DEFAULT_WEBSOCKET_URL
        );
        return new ClientNetworkConfig(http, webSocket);
    }

    public URI httpBaseUri() {
        return httpBaseUri;
    }

    public URI webSocketUri() {
        return webSocketUri;
    }

    /** Returns the 1-based bounded exponential reconnect delay. */
    public Duration reconnectDelay(int attempt) {
        if (attempt < 1 || attempt > MAX_RECONNECT_ATTEMPTS) {
            throw new IllegalArgumentException(
                "attempt must be between 1 and " + MAX_RECONNECT_ATTEMPTS);
        }
        long seconds = Math.min(1L << (attempt - 1), MAX_RECONNECT_DELAY.toSeconds());
        return Duration.ofSeconds(seconds);
    }

    private static URI validateAndNormalize(
        String value,
        String description,
        String firstScheme,
        String secondScheme
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }

        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(description + " is not a valid URI", exception);
        }
        String scheme = uri.getScheme() == null
            ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!firstScheme.equals(scheme) && !secondScheme.equals(scheme)) {
            throw new IllegalArgumentException(
                description + " must use " + firstScheme + " or " + secondScheme);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(description + " must include a host");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                description + " must not include user info, a query, or a fragment");
        }

        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }
}
