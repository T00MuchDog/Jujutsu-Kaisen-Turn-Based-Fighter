package com.jjktbf.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.server.auth.GuestAuthService;
import com.jjktbf.server.challenge.AcceptedMatchSetup;
import com.jjktbf.server.challenge.ChallengeService;
import com.jjktbf.server.config.ServerConfig;
import com.jjktbf.server.content.ContentCatalog;
import com.jjktbf.server.db.Database;
import com.jjktbf.server.match.MatchManager;
import com.jjktbf.server.service.ServiceErrorCode;
import com.jjktbf.server.service.ServiceException;
import com.jjktbf.server.transport.JavalinMatchConnection;
import io.javalin.Javalin;
import io.javalin.http.ContentTooLargeResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the multiplayer services and their runnable HTTP/WebSocket transport. */
public final class MultiplayerServer implements AutoCloseable {
    public static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiplayerServer.class);
    private static final String JSON_UTF_8 = "application/json; charset=utf-8";
    private static final int POLICY_VIOLATION_CLOSE = 1008;
    private static final int INTERNAL_ERROR_CLOSE = 1011;

    private final ServerConfig config;
    private final Database database;
    private final ContentCatalog contentCatalog;
    private final GuestAuthService guestAuthService;
    private final ChallengeService challengeService;
    private final MatchManager matchManager;
    private final ObjectMapper mapper;
    private final Javalin app;
    private final ConcurrentHashMap<Session, SocketContext> sockets =
        new ConcurrentHashMap<>();
    private final ScheduledExecutorService socketExpiryScheduler =
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "jjktbf-socket-expiry");
            thread.setDaemon(true);
            return thread;
        });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates and owns the complete production dependency graph. */
    public MultiplayerServer(ServerConfig config) {
        this(Objects.requireNonNull(config, "config"), createComponents(config), createMapper());
    }

    /**
     * Creates a server from supplied services. Ownership of the database and
     * match manager transfers to this server and they are closed with it.
     */
    public MultiplayerServer(
        ServerConfig config,
        Database database,
        ContentCatalog contentCatalog,
        GuestAuthService guestAuthService,
        ChallengeService challengeService,
        MatchManager matchManager,
        ObjectMapper mapper
    ) {
        this(
            Objects.requireNonNull(config, "config"),
            new Components(
                Objects.requireNonNull(database, "database"),
                Objects.requireNonNull(contentCatalog, "contentCatalog"),
                Objects.requireNonNull(guestAuthService, "guestAuthService"),
                Objects.requireNonNull(challengeService, "challengeService"),
                Objects.requireNonNull(matchManager, "matchManager")
            ),
            Objects.requireNonNull(mapper, "mapper")
        );
    }

    private MultiplayerServer(ServerConfig config, Components components, ObjectMapper mapper) {
        this.config = config;
        this.database = components.database();
        this.contentCatalog = components.contentCatalog();
        this.guestAuthService = components.guestAuthService();
        this.challengeService = components.challengeService();
        this.matchManager = components.matchManager();
        this.mapper = mapper;
        this.app = createApp();
        registerHttpRoutes();
        registerWebSocket();
        registerErrors();
    }

    public MultiplayerServer start() {
        return start(config.serverPort());
    }

    /** Starts on the supplied port; zero requests an ephemeral test port. */
    public synchronized MultiplayerServer start(int port) {
        if (closed.get()) {
            throw new IllegalStateException("MultiplayerServer is closed");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (started.get()) {
            throw new IllegalStateException("MultiplayerServer is already started");
        }
        if (ServerConfig.DEVELOPMENT_ONLY_DEFAULT_AUTH_TOKEN_SECRET.equals(
            config.authTokenSecret())) {
            LOGGER.warn(
                "AUTH_TOKEN_SECRET is not configured; using the stable development-only "
                    + "default. Set AUTH_TOKEN_SECRET for every production deployment."
            );
        }

        app.start(port);
        started.set(true);
        LOGGER.info(
            "Multiplayer server started port={} canonicalCharacters={}",
            app.port(),
            contentCatalog.characterSummaries().size()
        );
        return this;
    }

    /** Returns the bound port, including the selected ephemeral port after start. */
    public int port() {
        if (!started.get()) {
            throw new IllegalStateException("MultiplayerServer is not started");
        }
        return app.port();
    }

    private Javalin createApp() {
        return Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.http.defaultContentType = JSON_UTF_8;
            javalinConfig.http.maxRequestSize = MAX_MESSAGE_BYTES;
            javalinConfig.jsonMapper(new JavalinJackson(mapper));
            javalinConfig.jetty.wsFactoryConfig(factory -> {
                factory.setMaxTextMessageSize(MAX_MESSAGE_BYTES);
                factory.setMaxBinaryMessageSize(MAX_MESSAGE_BYTES);
            });
        });
    }

    private void registerHttpRoutes() {
        app.post("/api/guests", context -> {
            GuestCreateRequest request = readOptionalJson(context, GuestCreateRequest.class);
            writeJson(context, 201, guestAuthService.createGuest(request));
        });
        app.get("/api/session", context ->
            writeJson(context, 200, authenticate(context)));
        app.post("/api/challenges", context -> {
            SessionIdentity identity = authenticate(context);
            ChallengeCreateRequest request = readRequiredJson(
                context, ChallengeCreateRequest.class);
            writeJson(context, 201, challengeService.createChallenge(identity, request));
        });
        app.get("/api/challenges", context -> {
            SessionIdentity identity = authenticate(context);
            writeJson(context, 200, challengeService.listOpenChallenges(identity));
        });
        app.get("/api/challenges/{challengeId}", context -> {
            authenticate(context);
            String challengeId = requireUuidPath(context, "challengeId");
            writeJson(context, 200, challengeService.getChallenge(challengeId));
        });
        app.post("/api/challenges/{challengeId}/accept", context -> {
            SessionIdentity identity = authenticate(context);
            String challengeId = requireUuidPath(context, "challengeId");
            ChallengeAcceptRequest request = readRequiredJson(
                context, ChallengeAcceptRequest.class);
            AcceptedMatchSetup accepted = challengeService.acceptChallenge(
                identity, challengeId, request);
            matchManager.createMatch(accepted);
            MatchSetup setup = matchManager.getMatchSetup(identity, accepted.matchId());
            writeJson(context, 200, setup);
        });
        app.post("/api/challenges/{challengeId}/cancel", context -> {
            SessionIdentity identity = authenticate(context);
            String challengeId = requireUuidPath(context, "challengeId");
            writeJson(context, 200, challengeService.cancelChallenge(identity, challengeId));
        });
        app.get("/api/matches/{matchId}", context -> {
            SessionIdentity identity = authenticate(context);
            String matchId = requireUuidPath(context, "matchId");
            writeJson(context, 200, matchManager.getMatchSetup(identity, matchId));
        });
    }

    private void registerWebSocket() {
        app.ws("/ws/matches", ws -> {
            ws.onConnect(this::onSocketConnect);
            ws.onMessage(this::onSocketMessage);
            ws.onBinaryMessage(this::onSocketBinaryMessage);
            ws.onClose(this::onSocketClose);
            ws.onError(this::onSocketError);
        });
    }

    private void registerErrors() {
        app.exception(ServiceException.class, (exception, context) -> {
            LOGGER.info(
                "HTTP request rejected method={} path={} code={}",
                context.method(), context.path(), exception.code());
            writeJson(context, exception.suggestedStatus(), exception.toResponse());
        });
        app.exception(ContentTooLargeResponse.class, (exception, context) ->
            writeJson(context, 413, ErrorResponse.of(
                "REQUEST_TOO_LARGE", "The request body exceeds the allowed size.")));
        app.exception(HttpResponseException.class, (exception, context) -> {
            int status = exception.getStatus();
            String code = status == 400 ? "MALFORMED_REQUEST" : "HTTP_ERROR";
            String message = status == 400
                ? "The HTTP request is malformed."
                : "The HTTP request could not be processed.";
            writeJson(context, status, ErrorResponse.of(code, message));
        });
        app.exception(Exception.class, (exception, context) -> {
            LOGGER.error(
                "Unhandled HTTP failure method={} path={}",
                context.method(), context.path(), exception);
            writeJson(context, 500, ErrorResponse.of(
                "INTERNAL_ERROR", "The server could not complete the request."));
        });
        app.error(404, context -> writeJson(context, 404, ErrorResponse.of(
            "NOT_FOUND", "The requested route does not exist.")));
    }

    private void onSocketConnect(WsConnectContext context) {
        SessionIdentity identity;
        try {
            identity = guestAuthService.authenticate(bearerToken(
                context.header("Authorization")));
        } catch (ServiceException exception) {
            sendDirect(context, SocketMessage.error(ErrorResponse.of(
                "INVALID_TOKEN", "The guest token is invalid, expired, or revoked.")));
            safeClose(context, POLICY_VIOLATION_CLOSE, "Invalid token");
            LOGGER.info("WebSocket connection rejected code=INVALID_TOKEN");
            return;
        }

        String connectionId = UUID.randomUUID().toString();
        JavalinMatchConnection connection = new JavalinMatchConnection(
            connectionId, context.session, mapper);
        SocketContext socket = new SocketContext(identity, connectionId, connection);
        SocketContext previous = sockets.putIfAbsent(context.session, socket);
        if (previous != null) {
            connection.close();
            return;
        }
        long expiryDelay = Math.max(0L, identity.expiresAt() - System.currentTimeMillis());
        socket.expiryTask = socketExpiryScheduler.schedule(
            () -> expireSocket(context.session, socket),
            expiryDelay,
            TimeUnit.MILLISECONDS
        );
        LOGGER.info(
            "WebSocket authenticated playerId={} connectionId={}",
            identity.playerId(), connectionId);
    }

    private void onSocketMessage(WsMessageContext context) {
        SocketContext socket = sockets.get(context.session);
        if (socket == null) {
            return;
        }
        if (socket.identity.expiresAt() <= System.currentTimeMillis()) {
            sendSocketError(socket, ErrorResponse.of(
                "INVALID_TOKEN", "The guest token has expired."));
            disconnectSocket(context.session);
            safeClose(context, POLICY_VIOLATION_CLOSE, "Expired token");
            return;
        }
        if (context.message().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            sendSocketError(socket, malformedMessage());
            return;
        }

        SocketMessage message = null;
        try {
            message = mapper.readValue(context.message(), SocketMessage.class);
            synchronized (socket) {
                dispatchSocketMessage(socket, message);
            }
        } catch (InvalidFormatException exception) {
            if (exception.getTargetType() == MessageType.class) {
                sendSocketError(socket, ErrorResponse.of(
                    "UNSUPPORTED_MESSAGE_TYPE", "That WebSocket message type is not supported."));
            } else {
                sendSocketError(socket, malformedMessage());
            }
        } catch (JsonProcessingException | MalformedSocketMessageException exception) {
            sendSocketError(socket, malformedMessage());
        } catch (ServiceException exception) {
            LOGGER.info(
                "WebSocket request rejected playerId={} connectionId={} code={}",
                socket.identity.playerId(), socket.connectionId, exception.code());
            if (!sendCommandRejection(socket, message, exception.toResponse())) {
                sendSocketError(socket, exception.toResponse());
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Unhandled WebSocket message failure playerId={} connectionId={}",
                socket.identity.playerId(), socket.connectionId, exception);
            sendSocketError(socket, ErrorResponse.of(
                "INTERNAL_ERROR", "The server could not process the message."));
            if (isSubmitMessage(message)) {
                disconnectSocket(context.session);
                safeClose(context, INTERNAL_ERROR_CLOSE, "Command processing failed");
            }
        }
    }

    private void dispatchSocketMessage(SocketContext socket, SocketMessage message) {
        if (message == null || message.type() == null) {
            throw new MalformedSocketMessageException();
        }
        switch (message.type()) {
            case JOIN_MATCH -> joinSocketMatch(socket, message);
            case SUBMIT_ACTION -> submitSocketAction(socket, message);
            case PING -> {
                if (message.heartbeatTimestamp() == null) {
                    throw new MalformedSocketMessageException();
                }
                socket.connection.send(SocketMessage.pong(message.heartbeatTimestamp()));
            }
            case MATCH_JOINED, MATCH_STATE, COMMAND_REJECTED, PLAYER_CONNECTED,
                PLAYER_DISCONNECTED, MATCH_ENDED, PONG, ERROR -> sendSocketError(
                    socket,
                    ErrorResponse.of(
                        "UNSUPPORTED_MESSAGE_TYPE",
                        "Clients cannot send that WebSocket message type."
                    )
                );
        }
    }

    private void joinSocketMatch(SocketContext socket, SocketMessage message) {
        if (socket.joinedMatchId != null
            || !isCanonicalUuid(message.matchId())
            || isBlank(message.gameVersion())
            || message.protocolVersion() == null
            || isBlank(message.ruleset())) {
            throw new MalformedSocketMessageException();
        }
        matchManager.joinMatch(
            socket.identity,
            message.matchId(),
            message.gameVersion(),
            message.protocolVersion(),
            message.ruleset(),
            socket.connection
        );
        socket.joinedMatchId = message.matchId();
    }

    private void submitSocketAction(SocketContext socket, SocketMessage message) {
        String joinedMatchId = socket.joinedMatchId;
        if (joinedMatchId == null) {
            sendSocketError(socket, ErrorResponse.of(
                "MATCH_NOT_JOINED", "Join a match before submitting an action."));
            return;
        }
        if (message.command() == null
            || !joinedMatchId.equals(message.command().matchId())
            || (message.matchId() != null && !joinedMatchId.equals(message.matchId()))) {
            throw new MalformedSocketMessageException();
        }
        matchManager.submitAction(
            socket.identity,
            joinedMatchId,
            socket.connectionId,
            message.command()
        );
    }

    private void onSocketBinaryMessage(WsBinaryMessageContext context) {
        SocketContext socket = sockets.get(context.session);
        if (socket != null) {
            sendSocketError(socket, malformedMessage());
        }
    }

    private void onSocketClose(WsCloseContext context) {
        disconnectSocket(context.session);
    }

    private void onSocketError(WsErrorContext context) {
        SocketContext socket = sockets.get(context.session);
        if (socket != null) {
            LOGGER.warn(
                "WebSocket transport error playerId={} connectionId={} error={}",
                socket.identity.playerId(),
                socket.connectionId,
                context.error().getClass().getSimpleName()
            );
        }
        disconnectSocket(context.session);
        safeClose(context, INTERNAL_ERROR_CLOSE, "Connection error");
    }

    private void disconnectSocket(Session session) {
        SocketContext socket = sockets.remove(session);
        if (socket == null) {
            return;
        }
        String matchId;
        synchronized (socket) {
            socket.cancelExpiry();
            if (!socket.disconnected.compareAndSet(false, true)) {
                return;
            }
            socket.connection.markClosed();
            matchId = socket.joinedMatchId;
            if (matchId != null) {
                matchManager.disconnect(
                    matchId, socket.identity.playerId(), socket.connectionId);
            }
        }
        LOGGER.info(
            "WebSocket disconnected playerId={} connectionId={} matchId={}",
            socket.identity.playerId(), socket.connectionId, matchId);
    }

    private void expireSocket(Session session, SocketContext socket) {
        if (sockets.get(session) != socket) {
            return;
        }
        sendSocketError(socket, ErrorResponse.of(
            "INVALID_TOKEN", "The guest token has expired."));
        socket.connection.close();
        disconnectSocket(session);
        LOGGER.info(
            "WebSocket expired playerId={} connectionId={}",
            socket.identity.playerId(), socket.connectionId);
    }

    private SessionIdentity authenticate(Context context) {
        return guestAuthService.authenticate(bearerToken(context.header("Authorization")));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null
            || authorization.length() <= 7
            || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
            || authorization.indexOf(' ', 7) >= 0
            || authorization.indexOf('\t') >= 0
            || !authorization.equals(authorization.trim())) {
            throw invalidToken();
        }
        return authorization.substring(7);
    }

    private <T> T readRequiredJson(Context context, Class<T> type) {
        T value = readJson(context, type, false);
        if (value == null) {
            throw malformedRequest();
        }
        return value;
    }

    private <T> T readOptionalJson(Context context, Class<T> type) {
        return readJson(context, type, true);
    }

    private <T> T readJson(Context context, Class<T> type, boolean optional) {
        byte[] body = context.bodyAsBytes();
        if (body.length > MAX_MESSAGE_BYTES) {
            throw new ContentTooLargeResponse();
        }
        if (isBlank(body)) {
            if (optional) {
                return null;
            }
            throw malformedRequest();
        }
        if (!isJsonContentType(context.contentType())) {
            throw malformedRequest();
        }
        try {
            return mapper.readValue(body, type);
        } catch (JsonProcessingException exception) {
            throw malformedRequest();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not read the buffered request body", exception);
        }
    }

    private void writeJson(Context context, int status, Object value) {
        try {
            context.status(status)
                .contentType(JSON_UTF_8)
                .result(mapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize JSON response", exception);
        }
    }

    private String requireUuidPath(Context context, String name) {
        String value = context.pathParam(name);
        if (!isCanonicalUuid(value)) {
            throw malformedRequest();
        }
        return value;
    }

    private void sendSocketError(SocketContext socket, ErrorResponse error) {
        try {
            socket.connection.send(SocketMessage.error(socket.joinedMatchId, error));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "WebSocket error response send failed connectionId={} error={}",
                socket.connectionId, exception.getClass().getSimpleName());
        }
    }

    private boolean sendCommandRejection(
        SocketContext socket,
        SocketMessage message,
        ErrorResponse error
    ) {
        if (!isSubmitMessage(message)
            || message.command().commandId() == null
            || message.command().commandId().isBlank()) {
            return false;
        }
        try {
            socket.connection.send(SocketMessage.commandRejected(
                socket.joinedMatchId,
                message.command().commandId(),
                error,
                null
            ));
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "WebSocket command rejection send failed connectionId={} error={}",
                socket.connectionId,
                exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    private static boolean isSubmitMessage(SocketMessage message) {
        return message != null
            && message.type() == MessageType.SUBMIT_ACTION
            && message.command() != null;
    }

    private void sendDirect(WsContext context, SocketMessage message) {
        try {
            context.send(mapper.writeValueAsString(message));
        } catch (JsonProcessingException | RuntimeException exception) {
            LOGGER.warn(
                "WebSocket connection response send failed error={}",
                exception.getClass().getSimpleName());
        }
    }

    private static void safeClose(WsContext context, int status, String reason) {
        try {
            context.closeSession(status, reason);
        } catch (RuntimeException ignored) {
            // The peer may have closed while the final response was being sent.
        }
    }

    private static boolean isCanonicalUuid(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";");
        if (!"application/json".equalsIgnoreCase(parts[0].trim())) {
            return false;
        }
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim();
            if (parameter.regionMatches(true, 0, "charset=", 0, 8)
                && !"utf-8".equalsIgnoreCase(parameter.substring(8).trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(byte[] body) {
        for (byte value : body) {
            if (!Character.isWhitespace(value & 0xff)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ErrorResponse malformedMessage() {
        return ErrorResponse.of(
            "MALFORMED_MESSAGE", "The WebSocket message is malformed or incomplete.");
    }

    private static ServiceException malformedRequest() {
        return new ServiceException(
            ServiceErrorCode.MALFORMED_REQUEST, "The request body or path is malformed.");
    }

    private static ServiceException invalidToken() {
        return new ServiceException(
            ServiceErrorCode.INVALID_TOKEN,
            "The guest token is invalid, expired, or revoked.");
    }

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static Components createComponents(ServerConfig config) {
        Database database = new Database(config);
        MatchManager matchManager = null;
        try {
            ContentCatalog catalog = ContentCatalog.load();
            GuestAuthService authService = new GuestAuthService(database, config);
            ChallengeService challengeService = new ChallengeService(
                database, config, catalog);
            matchManager = new MatchManager(database, config);
            return new Components(
                database, catalog, authService, challengeService, matchManager);
        } catch (RuntimeException exception) {
            if (matchManager != null) {
                matchManager.close();
            }
            database.close();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        RuntimeException failure = null;
        if (started.get()) {
            try {
                app.stop();
            } catch (RuntimeException exception) {
                failure = exception;
            } finally {
                started.set(false);
            }
        }
        sockets.values().forEach(socket -> {
            socket.cancelExpiry();
            socket.connection.close();
        });
        sockets.clear();
        socketExpiryScheduler.shutdownNow();
        try {
            matchManager.close();
        } catch (RuntimeException exception) {
            failure = recordFailure(failure, exception);
        }
        try {
            database.close();
        } catch (RuntimeException exception) {
            failure = recordFailure(failure, exception);
        }
        LOGGER.info("Multiplayer server stopped");
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException recordFailure(
        RuntimeException current,
        RuntimeException next
    ) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private record Components(
        Database database,
        ContentCatalog contentCatalog,
        GuestAuthService guestAuthService,
        ChallengeService challengeService,
        MatchManager matchManager
    ) {
    }

    private static final class SocketContext {
        private final SessionIdentity identity;
        private final String connectionId;
        private final JavalinMatchConnection connection;
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private volatile String joinedMatchId;
        private volatile ScheduledFuture<?> expiryTask;

        private SocketContext(
            SessionIdentity identity,
            String connectionId,
            JavalinMatchConnection connection
        ) {
            this.identity = identity;
            this.connectionId = connectionId;
            this.connection = connection;
        }

        private void cancelExpiry() {
            ScheduledFuture<?> task = expiryTask;
            expiryTask = null;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static final class MalformedSocketMessageException extends RuntimeException {
    }
}
