package com.jjktbf.graphics.multiplayer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.SocketMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticated Java 17 WebSocket client with heartbeat and bounded reconnects. */
public final class MatchWebSocketClient implements MatchSocket {
    private static final int PROTOCOL_ERROR_CLOSE = 1002;
    private static final int MESSAGE_TOO_BIG_CLOSE = 1009;
    private static final Listener NO_OP_LISTENER = new Listener() {
    };

    public enum DisconnectReason {
        EXPLICIT,
        RETRIES_EXHAUSTED,
        CLIENT_CLOSED
    }

    public record ConnectionError(String code, String userMessage, Throwable cause) {
        public ConnectionError {
            if (code == null || code.isBlank()) {
                code = "CONNECTION_ERROR";
            }
            if (userMessage == null || userMessage.isBlank()) {
                userMessage = "The multiplayer connection failed.";
            }
        }

        @Override
        public String toString() {
            String causeType = cause == null ? "none" : cause.getClass().getSimpleName();
            return "ConnectionError[code=" + code + ", userMessage=" + userMessage
                + ", causeType=" + causeType + "]";
        }
    }

    /** Callbacks run on networking threads; graphical callers must marshal to their UI thread. */
    public interface Listener {
        default void onConnecting() {
        }

        default void onConnected() {
        }

        default void onReconnecting(int attempt, Duration delay) {
        }

        default void onDisconnected(DisconnectReason reason) {
        }

        default void onMessage(SocketMessage message) {
        }

        default void onError(ConnectionError error) {
        }
    }

    @FunctionalInterface
    interface WebSocketConnector {
        CompletableFuture<WebSocket> connect(
            URI uri,
            String bearerToken,
            WebSocket.Listener listener
        );
    }

    private final ClientNetworkConfig config;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final WebSocketConnector connector;
    private final Object lock = new Object();
    private final AtomicLong generationSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Listener listener = NO_OP_LISTENER;
    private String guestToken;
    private String matchId;
    private Attempt activeAttempt;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    private CompletableFuture<Void> initialConnection;
    private int reconnectAttempts;

    public MatchWebSocketClient(ClientNetworkConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = NetworkJson.newMapper();
        this.executor = NetworkExecutors.newBoundedDaemonPool(
            "jjktbf-websocket",
            ClientNetworkConfig.SOCKET_EXECUTOR_THREADS,
            ClientNetworkConfig.EXECUTOR_QUEUE_CAPACITY
        );
        this.scheduler = newScheduler();
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(ClientNetworkConfig.CONNECT_TIMEOUT)
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.connector = (uri, bearerToken, socketListener) -> client.newWebSocketBuilder()
            .connectTimeout(ClientNetworkConfig.CONNECT_TIMEOUT)
            .header("Authorization", "Bearer " + bearerToken)
            .buildAsync(uri, socketListener);
    }

    MatchWebSocketClient(
        ClientNetworkConfig config,
        ObjectMapper mapper,
        ExecutorService executor,
        ScheduledExecutorService scheduler,
        WebSocketConnector connector
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    @Override
    public CompletableFuture<Void> connect(
        String guestToken,
        String matchId,
        Listener listener
    ) {
        requireText(guestToken, "guestToken");
        requireText(matchId, "matchId");
        Objects.requireNonNull(listener, "listener");

        WebSocket previousSocket;
        CompletableFuture<Void> previousConnection;
        CompletableFuture<Void> connection;
        long generation;
        synchronized (lock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("MatchWebSocketClient is closed"));
            }
            generation = generationSequence.incrementAndGet();
            cancelTasksLocked();
            previousSocket = activeAttempt == null ? null : activeAttempt.webSocket;
            if (activeAttempt != null) {
                activeAttempt.terminal.set(true);
                activeAttempt.cancelJoinTimeout();
            }
            previousConnection = initialConnection;
            this.listener = listener;
            this.guestToken = guestToken;
            this.matchId = matchId;
            this.activeAttempt = null;
            this.reconnectAttempts = 0;
            this.initialConnection = new CompletableFuture<>();
            connection = initialConnection;
        }
        if (previousConnection != null && !previousConnection.isDone()) {
            previousConnection.completeExceptionally(
                new IllegalStateException("The match connection was replaced"));
        }
        closeQuietly(previousSocket, WebSocket.NORMAL_CLOSURE, "Connection replaced");
        if (isLifecycleCurrent(generation)) {
            notifyConnecting(listener);
        }
        beginAttempt(generation);
        return connection;
    }

    @Override
    public CompletableFuture<Void> send(SocketMessage message) {
        Objects.requireNonNull(message, "message");
        Attempt attempt;
        synchronized (lock) {
            attempt = activeAttempt;
            if (closed.get() || attempt == null || attempt.webSocket == null
                || attempt.terminal.get()) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("The match WebSocket is not connected"));
            }
        }
        return attempt.send(message);
    }

    @Override
    public void disconnect() {
        disconnectInternal(DisconnectReason.EXPLICIT, false);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        disconnectInternal(DisconnectReason.CLIENT_CLOSED, true);
        scheduler.shutdownNow();
        executor.shutdownNow();
        awaitTermination(scheduler);
        awaitTermination(executor);
    }

    private void beginAttempt(long expectedGeneration) {
        Attempt attempt;
        URI uri;
        String token;
        synchronized (lock) {
            if (closed.get() || guestToken == null || matchId == null
                || generationSequence.get() != expectedGeneration) {
                return;
            }
            attempt = new Attempt(
                expectedGeneration, matchId, listener, initialConnection);
            activeAttempt = attempt;
            uri = config.webSocketUri();
            token = guestToken;
        }

        try {
            connector.connect(uri, token, attempt).whenComplete((webSocket, failure) -> {
                if (failure != null) {
                    attempt.fail(
                        "CONNECTION_FAILED",
                        "Could not connect to the multiplayer match.",
                        unwrap(failure)
                    );
                } else if (!isActive(attempt)) {
                    closeQuietly(webSocket, WebSocket.NORMAL_CLOSURE, "Stale connection");
                }
            });
        } catch (RuntimeException exception) {
            attempt.fail(
                "CONNECTION_FAILED",
                "Could not connect to the multiplayer match.",
                exception
            );
        }
    }

    private boolean activate(Attempt attempt, WebSocket webSocket) {
        synchronized (lock) {
            if (!isActiveLocked(attempt) || attempt.terminal.get()) {
                return false;
            }
            attempt.webSocket = webSocket;
            attempt.lastPongNanos = System.nanoTime();
            cancelHeartbeatLocked();
            heartbeatTask = scheduler.scheduleAtFixedRate(
                () -> heartbeat(attempt),
                ClientNetworkConfig.HEARTBEAT_INTERVAL.toMillis(),
                ClientNetworkConfig.HEARTBEAT_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS
            );
            return true;
        }
    }

    private void heartbeat(Attempt attempt) {
        if (!isActive(attempt) || attempt.terminal.get()) {
            return;
        }
        long now = System.nanoTime();
        if (now - attempt.lastPongNanos > ClientNetworkConfig.HEARTBEAT_TIMEOUT.toNanos()) {
            WebSocket socket = attempt.webSocket;
            if (socket != null) {
                socket.abort();
            }
            attempt.fail(
                "HEARTBEAT_TIMEOUT",
                "The multiplayer connection stopped responding.",
                new IOException("WebSocket heartbeat timed out")
            );
            return;
        }
        long timestamp = System.currentTimeMillis();
        attempt.send(SocketMessage.ping(timestamp)).exceptionally(failure -> null);
    }

    private void connectionFailed(
        Attempt attempt,
        String code,
        String safeMessage,
        Throwable cause,
        boolean retryable
    ) {
        int nextAttempt;
        Duration delay;
        boolean exhausted;
        synchronized (lock) {
            if (!isActiveLocked(attempt) || closed.get()) {
                return;
            }
            attempt.cancelJoinTimeout();
            cancelHeartbeatLocked();
            activeAttempt = null;
            if (!retryable
                || reconnectAttempts >= ClientNetworkConfig.MAX_RECONNECT_ATTEMPTS) {
                exhausted = true;
                nextAttempt = -1;
                delay = Duration.ZERO;
                guestToken = null;
                matchId = null;
            } else {
                exhausted = false;
                nextAttempt = ++reconnectAttempts;
                delay = config.reconnectDelay(nextAttempt);
                long failedGeneration = attempt.generation;
                try {
                    reconnectTask = scheduler.schedule(
                        () -> runReconnect(failedGeneration),
                        delay.toMillis(),
                        TimeUnit.MILLISECONDS
                    );
                } catch (RejectedExecutionException exception) {
                    exhausted = true;
                    nextAttempt = -1;
                    guestToken = null;
                    matchId = null;
                    attempt.connectionFuture.completeExceptionally(exception);
                }
            }
        }

        abortQuietly(attempt.webSocket);

        if (!isLifecycleCurrent(attempt.generation)) {
            return;
        }
        notifyError(attempt.connectionListener,
            new ConnectionError(code, safeMessage, cause));
        if (exhausted) {
            attempt.connectionFuture.completeExceptionally(
                new IOException(safeMessage, cause));
            if (isLifecycleCurrent(attempt.generation)) {
                notifyDisconnected(
                    attempt.connectionListener, DisconnectReason.RETRIES_EXHAUSTED);
            }
        } else {
            if (isLifecycleCurrent(attempt.generation)) {
                notifyReconnecting(attempt.connectionListener, nextAttempt, delay);
            }
        }
    }

    private void runReconnect(long failedGeneration) {
        synchronized (lock) {
            if (closed.get() || guestToken == null || matchId == null
                || generationSequence.get() != failedGeneration) {
                return;
            }
            reconnectTask = null;
        }
        beginAttempt(failedGeneration);
    }

    private boolean markJoined(Attempt attempt) {
        synchronized (lock) {
            if (!isActiveLocked(attempt) || attempt.terminal.get()
                || !attempt.connectedNotified.compareAndSet(false, true)) {
                return false;
            }
            attempt.cancelJoinTimeout();
            reconnectAttempts = 0;
            return true;
        }
    }

    private void scheduleJoinTimeout(Attempt attempt) {
        synchronized (lock) {
            if (!isActiveLocked(attempt) || attempt.terminal.get()
                || attempt.connectedNotified.get()) {
                return;
            }
            attempt.joinTimeoutTask = scheduler.schedule(
                () -> joinTimedOut(attempt),
                ClientNetworkConfig.CONNECT_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void joinTimedOut(Attempt attempt) {
        synchronized (lock) {
            if (!isActiveLocked(attempt) || attempt.connectedNotified.get()
                || !attempt.terminal.compareAndSet(false, true)) {
                return;
            }
        }
        attempt.cancelJoinTimeout();
        connectionFailed(
            attempt,
            "JOIN_TIMEOUT",
            "The server did not confirm the multiplayer match join.",
            new IOException("WebSocket match join timed out"),
            true
        );
    }

    private void recordPong(Attempt attempt) {
        synchronized (lock) {
            if (!isActiveLocked(attempt)) {
                return;
            }
            attempt.lastPongNanos = System.nanoTime();
        }
    }

    private void disconnectInternal(DisconnectReason reason, boolean shuttingDown) {
        Attempt attempt;
        CompletableFuture<Void> unfinished;
        boolean hadConnection;
        Listener disconnectedListener;
        synchronized (lock) {
            generationSequence.incrementAndGet();
            cancelTasksLocked();
            attempt = activeAttempt;
            hadConnection = attempt != null || guestToken != null || matchId != null;
            if (attempt != null) {
                attempt.terminal.set(true);
                attempt.cancelJoinTimeout();
            }
            activeAttempt = null;
            guestToken = null;
            matchId = null;
            reconnectAttempts = 0;
            unfinished = initialConnection;
            initialConnection = null;
            disconnectedListener = listener;
        }
        if (unfinished != null && !unfinished.isDone()) {
            unfinished.completeExceptionally(
                new IllegalStateException("The match connection was closed"));
        }
        closeQuietly(
            attempt == null ? null : attempt.webSocket,
            WebSocket.NORMAL_CLOSURE,
            shuttingDown ? "Client closed" : "Disconnected"
        );
        if (hadConnection) {
            notifyDisconnected(disconnectedListener, reason);
        }
    }

    private boolean isActive(Attempt attempt) {
        synchronized (lock) {
            return isActiveLocked(attempt);
        }
    }

    private boolean isLifecycleCurrent(long generation) {
        synchronized (lock) {
            return !closed.get() && generationSequence.get() == generation;
        }
    }

    private boolean isActiveLocked(Attempt attempt) {
        return activeAttempt == attempt
            && generationSequence.get() == attempt.generation;
    }

    private void cancelTasksLocked() {
        cancelHeartbeatLocked();
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void cancelHeartbeatLocked() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private SocketMessage parseServerMessage(String json, String expectedMatchId)
        throws ProtocolMessageException {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new ProtocolMessageException("Message is not a JSON object");
            }
            JsonNode typeNode = root.get("type");
            if (typeNode == null || !typeNode.isTextual()) {
                throw new ProtocolMessageException("Message type is missing");
            }
            MessageType type;
            try {
                type = MessageType.valueOf(typeNode.textValue());
            } catch (IllegalArgumentException exception) {
                throw new ProtocolMessageException("Message type is unsupported", exception);
            }
            if (!isServerMessage(type)) {
                throw new ProtocolMessageException("Message type is not server-originated");
            }

            SocketMessage message = mapper.treeToValue(root, SocketMessage.class);
            validateServerMessage(message, expectedMatchId);
            return message;
        } catch (JsonProcessingException exception) {
            throw new ProtocolMessageException("Message JSON is malformed", exception);
        }
    }

    private static boolean isServerMessage(MessageType type) {
        return switch (type) {
            case MATCH_JOINED, MATCH_STATE, COMMAND_REJECTED, PLAYER_CONNECTED,
                PLAYER_DISCONNECTED, MATCH_ENDED, PONG, ERROR -> true;
            case JOIN_MATCH, SUBMIT_ACTION, PING -> false;
        };
    }

    private static void validateServerMessage(
        SocketMessage message,
        String expectedMatchId
    ) throws ProtocolMessageException {
        if (message == null || message.type() == null) {
            throw new ProtocolMessageException("Message is incomplete");
        }
        switch (message.type()) {
            case MATCH_JOINED -> {
                requireMatch(message, expectedMatchId);
                requireState(message, expectedMatchId);
                if (message.playerId() == null || message.playerSide() == null) {
                    throw new ProtocolMessageException("Join confirmation is incomplete");
                }
            }
            case MATCH_STATE, MATCH_ENDED -> {
                requireMatch(message, expectedMatchId);
                requireState(message, expectedMatchId);
            }
            case COMMAND_REJECTED -> {
                requireMatch(message, expectedMatchId);
                if (message.commandId() == null || message.commandId().isBlank()
                    || message.error() == null) {
                    throw new ProtocolMessageException("Command rejection is incomplete");
                }
                if (message.state() != null
                    && !expectedMatchId.equals(message.state().matchId())) {
                    throw new ProtocolMessageException("Rejection state belongs to another match");
                }
            }
            case PLAYER_CONNECTED, PLAYER_DISCONNECTED -> {
                requireMatch(message, expectedMatchId);
                if (message.playerId() == null || message.playerSide() == null) {
                    throw new ProtocolMessageException("Player event is incomplete");
                }
                if (message.type() == MessageType.PLAYER_DISCONNECTED
                    && message.disconnectDeadline() == null) {
                    throw new ProtocolMessageException("Disconnect deadline is missing");
                }
            }
            case PONG -> {
                if (message.heartbeatTimestamp() == null) {
                    throw new ProtocolMessageException("Pong timestamp is missing");
                }
            }
            case ERROR -> requireError(message.error());
            case JOIN_MATCH, SUBMIT_ACTION, PING ->
                throw new ProtocolMessageException("Client message received from server");
        }
    }

    private static void requireMatch(SocketMessage message, String expectedMatchId)
        throws ProtocolMessageException {
        if (!expectedMatchId.equals(message.matchId())) {
            throw new ProtocolMessageException("Message belongs to another match");
        }
    }

    private static void requireState(SocketMessage message, String expectedMatchId)
        throws ProtocolMessageException {
        if (message.state() == null || !expectedMatchId.equals(message.state().matchId())) {
            throw new ProtocolMessageException("Authoritative state is missing or mismatched");
        }
        if (message.stateVersion() != null
            && message.stateVersion() != message.state().stateVersion()) {
            throw new ProtocolMessageException("State version is inconsistent");
        }
    }

    private static void requireError(ErrorResponse error) throws ProtocolMessageException {
        if (error == null || error.code() == null || error.code().isBlank()
            || error.message() == null || error.message().isBlank()) {
            throw new ProtocolMessageException("Error message is incomplete");
        }
    }

    private void notifyConnecting(Listener target) {
        safeListenerCall(target::onConnecting);
    }

    private void notifyConnected(Listener target) {
        safeListenerCall(target::onConnected);
    }

    private void notifyReconnecting(Listener target, int attempt, Duration delay) {
        safeListenerCall(() -> target.onReconnecting(attempt, delay));
    }

    private void notifyDisconnected(Listener target, DisconnectReason reason) {
        safeListenerCall(() -> target.onDisconnected(reason));
    }

    private void notifyMessage(Listener target, SocketMessage message) {
        safeListenerCall(() -> target.onMessage(message));
    }

    private void notifyError(Listener target, ConnectionError error) {
        safeListenerCall(() -> target.onError(error));
    }

    private static void safeListenerCall(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            // A UI listener cannot be allowed to break transport callbacks,
            // but record the failure so UI bugs are observable instead of silent.
            com.jjktbf.AppPaths.logException(failure);
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "jjktbf-websocket-scheduler");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor scheduler =
            (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, factory);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private static void closeQuietly(WebSocket socket, int status, String reason) {
        if (socket == null) {
            return;
        }
        try {
            if (!socket.isOutputClosed()) {
                socket.sendClose(status, reason);
            }
        } catch (RuntimeException ignored) {
            socket.abort();
        }
    }

    private static void abortQuietly(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.abort();
        } catch (RuntimeException ignored) {
            // The failed transport may already be closed.
        }
    }

    private static void awaitTermination(ExecutorService service) {
        try {
            service.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private final class Attempt implements WebSocket.Listener {
        private final long generation;
        private final String expectedMatchId;
        private final Listener connectionListener;
        private final CompletableFuture<Void> connectionFuture;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean connectedNotified = new AtomicBoolean();
        private final StringBuilder partialText = new StringBuilder();

        private volatile WebSocket webSocket;
        private volatile long lastPongNanos;
        private volatile ScheduledFuture<?> joinTimeoutTask;

        private Attempt(
            long generation,
            String expectedMatchId,
            Listener connectionListener,
            CompletableFuture<Void> connectionFuture
        ) {
            this.generation = generation;
            this.expectedMatchId = expectedMatchId;
            this.connectionListener = connectionListener;
            this.connectionFuture = connectionFuture;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!activate(this, webSocket)) {
                closeQuietly(webSocket, WebSocket.NORMAL_CLOSURE, "Stale connection");
                return;
            }
            try {
                webSocket.request(1);
                scheduleJoinTimeout(this);
                send(SocketMessage.joinMatch(expectedMatchId)).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        fail(
                            "CONNECTION_FAILED",
                            "Could not join the multiplayer match.",
                            unwrap(failure)
                        );
                    }
                });
            } catch (RuntimeException exception) {
                fail(
                    "CONNECTION_FAILED",
                    "Could not initialize the multiplayer match connection.",
                    exception
                );
            }
        }

        @Override
        public CompletionStage<?> onText(
            WebSocket webSocket,
            CharSequence data,
            boolean last
        ) {
            if (!isActive(this) || terminal.get()) {
                requestNext(webSocket);
                return CompletableFuture.completedFuture(null);
            }

            String complete = null;
            synchronized (partialText) {
                partialText.append(data);
                int bytes = partialText.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > ClientNetworkConfig.MAX_SOCKET_MESSAGE_BYTES) {
                    partialText.setLength(0);
                    closeQuietly(webSocket, MESSAGE_TOO_BIG_CLOSE, "Message too large");
                    fail(
                        "MESSAGE_TOO_LARGE",
                        "The server sent an oversized multiplayer message.",
                        new IOException("WebSocket message exceeded the configured limit")
                    );
                } else if (last) {
                    complete = partialText.toString();
                    partialText.setLength(0);
                }
            }

            if (complete != null && !terminal.get()) {
                try {
                    SocketMessage message = parseServerMessage(complete, expectedMatchId);
                    if (message.type() == MessageType.PONG) {
                        recordPong(this);
                    } else if (message.type() == MessageType.MATCH_JOINED) {
                        boolean joined = markJoined(this);
                        if (isActive(this)) {
                            notifyMessage(connectionListener, message);
                        }
                        if (joined && isActive(this)) {
                            connectionFuture.complete(null);
                            if (isActive(this)) {
                                notifyConnected(connectionListener);
                            }
                        }
                        requestNext(webSocket);
                        return CompletableFuture.completedFuture(null);
                    } else if (message.type() == MessageType.ERROR
                        && !connectedNotified.get()) {
                        failPermanently(
                            message.error().code(),
                            message.error().message(),
                            new IOException("Server rejected the WebSocket join")
                        );
                    }
                    if (isActive(this)) {
                        notifyMessage(connectionListener, message);
                    }
                } catch (ProtocolMessageException exception) {
                    closeQuietly(webSocket, PROTOCOL_ERROR_CLOSE, "Invalid server message");
                    fail(
                        "MALFORMED_MESSAGE",
                        "The server sent an invalid multiplayer message.",
                        exception
                    );
                }
            }
            requestNext(webSocket);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(
            WebSocket webSocket,
            ByteBuffer data,
            boolean last
        ) {
            closeQuietly(webSocket, PROTOCOL_ERROR_CLOSE, "Binary messages are unsupported");
            fail(
                "MALFORMED_MESSAGE",
                "The server sent an unsupported multiplayer message.",
                new IOException("Binary WebSocket message received")
            );
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
            WebSocket webSocket,
            int statusCode,
            String reason
        ) {
            fail(
                "CONNECTION_CLOSED",
                "The multiplayer connection was closed.",
                new IOException("WebSocket closed with status " + statusCode)
            );
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(
                "CONNECTION_FAILED",
                "The multiplayer connection failed.",
                error
            );
        }

        private CompletableFuture<Void> send(SocketMessage message) {
            WebSocket socket = webSocket;
            if (socket == null || terminal.get() || !isActive(this)) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("The match WebSocket is not connected"));
            }
            String json;
            try {
                json = mapper.writeValueAsString(message);
            } catch (JsonProcessingException exception) {
                return CompletableFuture.failedFuture(exception);
            }

            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                socket.sendText(json, true).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        Throwable cause = unwrap(failure);
                        result.completeExceptionally(cause);
                        fail(
                            "SEND_FAILED",
                            "The multiplayer command could not be sent.",
                            cause
                        );
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                fail(
                    "SEND_FAILED",
                    "The multiplayer command could not be sent.",
                    exception
                );
            }
            return result;
        }

        private void fail(String code, String safeMessage, Throwable cause) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelJoinTimeout();
            connectionFailed(this, code, safeMessage, cause, true);
        }

        private void failPermanently(String code, String safeMessage, Throwable cause) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelJoinTimeout();
            connectionFailed(this, code, safeMessage, cause, false);
        }

        private void cancelJoinTimeout() {
            ScheduledFuture<?> timeout = joinTimeoutTask;
            joinTimeoutTask = null;
            if (timeout != null) {
                timeout.cancel(false);
            }
        }

        private void requestNext(WebSocket webSocket) {
            try {
                if (isActive(this) && !terminal.get()) {
                    webSocket.request(1);
                }
            } catch (RuntimeException exception) {
                fail(
                    "CONNECTION_FAILED",
                    "The multiplayer connection failed.",
                    exception
                );
            }
        }
    }

    private static final class ProtocolMessageException extends Exception {
        private ProtocolMessageException(String message) {
            super(message);
        }

        private ProtocolMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
