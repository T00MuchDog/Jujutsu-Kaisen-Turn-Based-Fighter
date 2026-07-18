package com.jjktbf.server.auth;

import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.server.config.ServerConfig;
import com.jjktbf.server.db.Database;
import com.jjktbf.server.db.DatabaseException;
import com.jjktbf.server.service.ServiceErrorCode;
import com.jjktbf.server.service.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates and authenticates passwordless guest sessions. */
public final class GuestAuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuestAuthService.class);

    public static final int TOKEN_ENTROPY_BYTES = 32;
    public static final Duration SESSION_DURATION = Duration.ofDays(30);

    private static final Pattern DISPLAY_NAME =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{2,23}");
    private static final int GENERATED_NAME_ATTEMPTS = 100;
    private static final Base64.Encoder TOKEN_ENCODER =
        Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final Database database;
    private final GuestRepository repository;
    private final byte[] tokenSecret;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public GuestAuthService(Database database, ServerConfig config) {
        this(database, config, Clock.systemUTC(), new SecureRandom());
    }

    public GuestAuthService(Database database, ServerConfig config, Clock clock) {
        this(database, config, clock, new SecureRandom());
    }

    public GuestAuthService(
        Database database,
        ServerConfig config,
        Clock clock,
        SecureRandom secureRandom
    ) {
        this.database = Objects.requireNonNull(database, "database");
        Objects.requireNonNull(config, "config");
        this.repository = new GuestRepository();
        this.tokenSecret = config.authTokenSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public GuestCreateResponse createGuest(Optional<String> requestedDisplayName) {
        String requested = requestedDisplayName == null
            ? null : requestedDisplayName.map(String::trim).orElse(null);
        if (requested != null && requested.isBlank()) {
            requested = null;
        }
        if (requested != null) {
            validateDisplayName(requested);
            return createWithName(requested, false);
        }

        for (int attempt = 0; attempt < GENERATED_NAME_ATTEMPTS; attempt++) {
            byte[] suffix = new byte[9];
            secureRandom.nextBytes(suffix);
            String generated = "Guest-" + TOKEN_ENCODER.encodeToString(suffix);
            try {
                return createWithName(generated, true);
            } catch (NameCollisionException ignored) {
                // Generate a fresh suffix and retry the unique display-name insert.
            }
        }
        throw new ServiceException(
            ServiceErrorCode.INTERNAL_ERROR,
            "Could not allocate a guest display name.");
    }

    public GuestCreateResponse createGuest(GuestCreateRequest request) {
        return createGuest(Optional.ofNullable(request == null ? null : request.displayName()));
    }

    public SessionIdentity authenticate(String rawToken) {
        TokenParts token = parseToken(rawToken);
        GuestSessionRecord session = database.withConnection(connection ->
            repository.findSession(connection, token.sessionId()).orElse(null));
        if (session == null || session.revokedAt() != null
            || session.expiresAt() <= clock.millis()) {
            throw invalidToken();
        }

        byte[] expected = decodeHash(session.tokenHash());
        byte[] actual = hashToken(rawToken);
        if (expected == null || !MessageDigest.isEqual(expected, actual)) {
            throw invalidToken();
        }
        return new SessionIdentity(
            session.playerId(), session.displayName(), session.expiresAt());
    }

    private GuestCreateResponse createWithName(String displayName, boolean generated) {
        long createdAt = clock.millis();
        long expiresAt = Math.addExact(createdAt, SESSION_DURATION.toMillis());
        String playerId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        byte[] entropy = new byte[TOKEN_ENTROPY_BYTES];
        secureRandom.nextBytes(entropy);
        String token = sessionId + "." + TOKEN_ENCODER.encodeToString(entropy);
        String tokenHash = TOKEN_ENCODER.encodeToString(hashToken(token));
        String normalizedName = displayName.toLowerCase(Locale.ROOT);

        try {
            GuestCreateResponse response = database.transaction(connection -> {
                if (repository.displayNameExists(connection, normalizedName)) {
                    throw new NameCollisionException();
                }
                repository.insertGuest(
                    connection, playerId, displayName, normalizedName, createdAt);
                repository.insertSession(
                    connection, sessionId, playerId, tokenHash, createdAt, expiresAt);
                return new GuestCreateResponse(
                    new SessionIdentity(playerId, displayName, expiresAt), token);
            });
            LOGGER.info(
                "Guest created playerId={} displayName={}",
                response.identity().playerId(),
                response.identity().displayName()
            );
            return response;
        } catch (NameCollisionException exception) {
            if (generated) {
                throw exception;
            }
            throw new ServiceException(
                ServiceErrorCode.DISPLAY_NAME_TAKEN,
                "That display name is already in use.");
        } catch (DatabaseException exception) {
            if (isConstraintViolation(exception)) {
                if (generated) {
                    throw new NameCollisionException();
                }
                throw new ServiceException(
                    ServiceErrorCode.DISPLAY_NAME_TAKEN,
                    "That display name is already in use.");
            }
            throw exception;
        }
    }

    private byte[] hashToken(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
            return mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static TokenParts parseToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }
        int separator = rawToken.indexOf('.');
        if (separator <= 0 || separator != rawToken.lastIndexOf('.')) {
            throw invalidToken();
        }
        String sessionId = rawToken.substring(0, separator);
        String encodedEntropy = rawToken.substring(separator + 1);
        try {
            UUID parsedId = UUID.fromString(sessionId);
            byte[] entropy = TOKEN_DECODER.decode(encodedEntropy);
            if (!parsedId.toString().equals(sessionId) || entropy.length != TOKEN_ENTROPY_BYTES) {
                throw invalidToken();
            }
            return new TokenParts(sessionId);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static byte[] decodeHash(String encodedHash) {
        try {
            return TOKEN_DECODER.decode(encodedHash);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void validateDisplayName(String displayName) {
        if (!DISPLAY_NAME.matcher(displayName).matches()) {
            throw new ServiceException(
                ServiceErrorCode.INVALID_DISPLAY_NAME,
                "Display names must be 3-24 characters and use only letters, numbers, spaces, '-' or '_'.");
        }
    }

    private static boolean isConstraintViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                && sqlException.getSQLState() != null
                && sqlException.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private static ServiceException invalidToken() {
        return new ServiceException(
            ServiceErrorCode.INVALID_TOKEN,
            "The guest token is invalid, expired, or revoked.");
    }

    private record TokenParts(String sessionId) {
    }

    private static final class NameCollisionException extends RuntimeException {
    }
}
