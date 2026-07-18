package com.jjktbf.graphics.multiplayer;

import com.jjktbf.AppPaths;
import com.jjktbf.multiplayer.protocol.ActionCommand;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.SocketMessage;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates the match socket with authoritative, thread-safe client state. */
public final class MultiplayerMatchService implements AutoCloseable {
    public enum SubmissionStatus {
        SENT,
        NO_MATCH,
        NOT_CONNECTED,
        ALREADY_PENDING,
        MATCH_ENDED,
        SERVICE_CLOSED
    }

    public enum CommandCompletionStatus {
        AUTHORITATIVE_STATE,
        REJECTED,
        CONNECTION_FAILED,
        SEND_FAILED,
        MATCH_ENDED,
        SERVICE_CLOSED,
        NOT_SENT
    }

    public record CommandOutcome(
        String commandId,
        CommandCompletionStatus status,
        ErrorResponse error,
        MatchState state
    ) {
        public boolean accepted() {
            return status == CommandCompletionStatus.AUTHORITATIVE_STATE;
        }
    }

    public record PlanSubmission(
        SubmissionStatus status,
        String commandId,
        CompletableFuture<CommandOutcome> completion
    ) {
        public boolean sent() {
            return status == SubmissionStatus.SENT;
        }
    }

    /** Callbacks run on networking threads; graphical callers must marshal to their UI thread. */
    public interface Listener {
        default void onConnectionStateChanged(
            MultiplayerSession.ConnectionState state
        ) {
        }

        default void onReconnecting(int attempt, Duration delay) {
        }

        default void onDisconnected(MatchWebSocketClient.DisconnectReason reason) {
        }

        default void onMatchState(MatchState state) {
        }

        default void onPlayerConnectionChanged(SocketMessage message) {
        }

        default void onCommandCompleted(CommandOutcome outcome) {
        }

        default void onCommandRejected(String commandId, ErrorResponse error) {
        }

        default void onMatchEnded(MatchState state) {
        }

        default void onSocketMessage(SocketMessage message) {
        }

        default void onError(String code, String userMessage, Throwable cause) {
        }
    }

    private final MultiplayerSession session;
    private final MatchSocket socket;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object commandLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private PendingSubmission pendingSubmission;
    private long connectionGeneration;

    public MultiplayerMatchService(
        MultiplayerSession session,
        MatchWebSocketClient socket
    ) {
        this(session, (MatchSocket) socket);
    }

    MultiplayerMatchService(MultiplayerSession session, MatchSocket socket) {
        this.session = Objects.requireNonNull(session, "session");
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Opens or replaces the socket for an HTTP-provided match setup. */
    public CompletableFuture<Void> connect(MatchSetup setup) {
        Objects.requireNonNull(setup, "setup");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("MultiplayerMatchService is closed"));
        }
        GuestCredentials credentials = session.guestCredentials().orElse(null);
        if (credentials == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("A guest session is required"));
        }
        if (!credentials.identity().playerId().equals(setup.playerId())) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Match setup belongs to another guest"));
        }

        PendingSubmission replaced;
        MatchWebSocketClient.Listener connectionListener;
        CompletableFuture<Void> connection;
        synchronized (commandLock) {
            replaced = pendingSubmission;
            pendingSubmission = null;
            session.clearPendingCommand();
            session.setMatchSetup(setup);
            connectionListener = new SocketListener(++connectionGeneration);
            connection = socket.connect(
                credentials.token(), setup.matchId(), connectionListener);
        }
        completePending(
            replaced,
            CommandCompletionStatus.CONNECTION_FAILED,
            ErrorResponse.of("MATCH_REPLACED", "The active match connection was replaced."),
            setup.state()
        );
        return connection;
    }

    public CompletableFuture<Void> resume(MatchSetup setup) {
        return connect(setup);
    }

    /**
     * Ends the current match connection without closing this app-wide service.
     * Any transport callbacks already in flight belong to an invalidated
     * generation and therefore cannot repopulate the cleared session.
     */
    public void disconnect() {
        PendingSubmission pending;
        synchronized (commandLock) {
            if (closed.get()) {
                return;
            }
            connectionGeneration++;
            pending = pendingSubmission;
            pendingSubmission = null;
            session.clearPendingCommand();
            session.clearCurrentChallenge();
            session.clearMatch();
            socket.disconnect();
        }
        completePending(
            pending,
            CommandCompletionStatus.CONNECTION_FAILED,
            ErrorResponse.of("MATCH_DISCONNECTED", "The match connection was closed."),
            null
        );
        notifyConnectionState(MultiplayerSession.ConnectionState.DISCONNECTED);
    }

    /** Sends intent only; the session state changes solely on a server snapshot. */
    public PlanSubmission submitPlan(List<PlanPlacement> placements) {
        synchronized (commandLock) {
            if (closed.get()) {
                return notSent(SubmissionStatus.SERVICE_CLOSED);
            }
            String commandId = UUID.randomUUID().toString();
            MultiplayerSession.CommandStart start = session.beginPlanCommand(
                commandId, placements);
            if (!start.ready()) {
                return notSent(mapStatus(start.status()));
            }

            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            PendingSubmission pending = new PendingSubmission(start.command(), completion);
            pendingSubmission = pending;
            try {
                socket.send(SocketMessage.submitAction(start.command()))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            failSend(pending, failure);
                        }
                    });
            } catch (RuntimeException exception) {
                failSend(pending, exception);
            }
            return new PlanSubmission(SubmissionStatus.SENT, commandId, completion);
        }
    }

    private void handleStateMessage(SocketMessage message, boolean endedMessage) {
        MatchState state = message.state();
        PendingSubmission completed = null;
        CommandCompletionStatus completionStatus = null;
        boolean applied;
        boolean joined = message.type() == MessageType.MATCH_JOINED;
        boolean connectionChanged = false;
        synchronized (commandLock) {
            session.recordSocketMessage(message);
            MultiplayerSession.StateUpdate update = session.updateAuthoritativeState(state);
            applied = update.applied();
            if (applied && joined) {
                session.clearError();
                connectionChanged = session.setConnectionState(
                    MultiplayerSession.ConnectionState.CONNECTED);
            }
            if (pendingSubmission != null && message.commandId() != null
                && pendingSubmission.command.commandId().equals(message.commandId())) {
                completed = pendingSubmission;
                pendingSubmission = null;
                session.clearPendingCommand(completed.command.commandId());
                completionStatus = CommandCompletionStatus.AUTHORITATIVE_STATE;
            } else if (pendingSubmission != null
                && update.clearedPendingCommand() != null) {
                completed = pendingSubmission;
                pendingSubmission = null;
                completionStatus = CommandCompletionStatus.MATCH_ENDED;
            }
        }
        if (!applied) {
            notifyError(
                "STALE_MATCH_MESSAGE",
                "An out-of-date multiplayer state was ignored.",
                null
            );
            return;
        }

        completePending(completed, completionStatus, null, state);
        if (connectionChanged) {
            notifyConnectionState(MultiplayerSession.ConnectionState.CONNECTED);
        }
        notifyMatchState(state);
        if (endedMessage) {
            notifyMatchEnded(state);
        }
        notifySocketMessage(message);
        if (endedMessage) {
            socket.disconnect();
        }
    }

    private void handleCommandRejected(SocketMessage message) {
        PendingSubmission completed = null;
        PendingSubmission ended = null;
        MatchState state = message.state();
        synchronized (commandLock) {
            session.recordSocketMessage(message);
            MultiplayerSession.StateUpdate update = state == null
                ? null : session.updateAuthoritativeState(state);
            ErrorResponse error = message.error();
            session.recordError(error.code(), error.message());

            if (pendingSubmission != null
                && pendingSubmission.command.commandId().equals(message.commandId())) {
                completed = pendingSubmission;
                pendingSubmission = null;
                session.clearPendingCommand(message.commandId());
            } else if (pendingSubmission != null && update != null
                && update.clearedPendingCommand() != null
                && pendingSubmission.command.commandId().equals(
                    update.clearedPendingCommand().commandId())) {
                ended = pendingSubmission;
                pendingSubmission = null;
            }
        }

        completePending(
            completed,
            CommandCompletionStatus.REJECTED,
            message.error(),
            state
        );
        completePending(
            ended,
            CommandCompletionStatus.MATCH_ENDED,
            message.error(),
            state
        );
        if (state != null) {
            notifyMatchState(state);
        }
        notifyCommandRejected(message.commandId(), message.error());
        notifySocketMessage(message);
    }

    private void handlePlayerEvent(SocketMessage message) {
        session.recordSocketMessage(message);
        notifyPlayerConnection(message);
        notifySocketMessage(message);
    }

    private void handleServerError(SocketMessage message) {
        ErrorResponse error = message.error();
        session.recordSocketMessage(message);
        session.recordError(error.code(), error.message());
        notifyError(error.code(), error.message(), null);
        notifySocketMessage(message);
    }

    private void handlePong(SocketMessage message) {
        session.recordSocketMessage(message);
        notifySocketMessage(message);
    }

    private void failSend(PendingSubmission pending, Throwable failure) {
        boolean removed;
        synchronized (commandLock) {
            removed = pendingSubmission == pending;
            if (removed) {
                pendingSubmission = null;
                session.clearPendingCommand(pending.command.commandId());
                session.recordError(
                    "SEND_FAILED", "The multiplayer command could not be sent.");
            }
        }
        if (!removed) {
            return;
        }
        completePending(
            pending,
            CommandCompletionStatus.SEND_FAILED,
            ErrorResponse.of("SEND_FAILED", "The multiplayer command could not be sent."),
            session.latestState().orElse(null)
        );
        notifyError(
            "SEND_FAILED", "The multiplayer command could not be sent.", failure);
    }

    private PendingSubmission transitionAfterConnectionFailure(
        MultiplayerSession.ConnectionState state
    ) {
        PendingSubmission pending;
        synchronized (commandLock) {
            pending = pendingSubmission;
            pendingSubmission = null;
            session.clearPendingCommand();
            session.setConnectionState(state);
        }
        return pending;
    }

    private void completePending(
        PendingSubmission pending,
        CommandCompletionStatus status,
        ErrorResponse error,
        MatchState state
    ) {
        if (pending == null || status == null) {
            return;
        }
        CommandOutcome outcome = new CommandOutcome(
            pending.command.commandId(), status, error, state);
        if (pending.completion.complete(outcome)) {
            notifyCommandCompleted(outcome);
        }
    }

    private static PlanSubmission notSent(SubmissionStatus status) {
        CommandOutcome outcome = new CommandOutcome(
            null, CommandCompletionStatus.NOT_SENT, null, null);
        return new PlanSubmission(
            status, null, CompletableFuture.completedFuture(outcome));
    }

    private static SubmissionStatus mapStatus(
        MultiplayerSession.CommandStartStatus status
    ) {
        return switch (status) {
            case READY -> SubmissionStatus.SENT;
            case NO_MATCH -> SubmissionStatus.NO_MATCH;
            case NOT_CONNECTED -> SubmissionStatus.NOT_CONNECTED;
            case ALREADY_PENDING -> SubmissionStatus.ALREADY_PENDING;
            case MATCH_ENDED -> SubmissionStatus.MATCH_ENDED;
        };
    }

    private void setConnectionState(MultiplayerSession.ConnectionState state) {
        session.setConnectionState(state);
        notifyConnectionState(state);
    }

    private void notifyConnectionState(MultiplayerSession.ConnectionState state) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onConnectionStateChanged(state));
        }
    }

    private void notifyMatchState(MatchState state) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onMatchState(state));
        }
    }

    private void notifyReconnecting(int attempt, Duration delay) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onReconnecting(attempt, delay));
        }
    }

    private void notifyDisconnected(MatchWebSocketClient.DisconnectReason reason) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onDisconnected(reason));
        }
    }

    private void notifyPlayerConnection(SocketMessage message) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onPlayerConnectionChanged(message));
        }
    }

    private void notifyCommandCompleted(CommandOutcome outcome) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onCommandCompleted(outcome));
        }
    }

    private void notifyCommandRejected(String commandId, ErrorResponse error) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onCommandRejected(commandId, error));
        }
    }

    private void notifyMatchEnded(MatchState state) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onMatchEnded(state));
        }
    }

    private void notifySocketMessage(SocketMessage message) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onSocketMessage(message));
        }
    }

    private void notifyError(String code, String message, Throwable cause) {
        for (Listener listener : listeners) {
            safeCall(() -> listener.onError(code, message, cause));
        }
    }

    private static void safeCall(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            // A screen listener cannot break network/session state processing,
            // but record the failure so UI bugs are observable instead of silent.
            AppPaths.logException(failure);
        }
    }

    private static boolean isTerminal(MatchStatus status) {
        return status == MatchStatus.ENDED || status == MatchStatus.ABANDONED;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PendingSubmission pending;
        synchronized (commandLock) {
            connectionGeneration++;
            pending = pendingSubmission;
            pendingSubmission = null;
            session.clearPendingCommand();
            session.setConnectionState(MultiplayerSession.ConnectionState.DISCONNECTED);
            socket.close();
        }
        completePending(
            pending,
            CommandCompletionStatus.SERVICE_CLOSED,
            ErrorResponse.of("SERVICE_CLOSED", "The multiplayer match service was closed."),
            session.latestState().orElse(null)
        );
        listeners.clear();
    }

    private final class SocketListener implements MatchWebSocketClient.Listener {
        private final long generation;

        private SocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onConnecting() {
            synchronized (commandLock) {
                if (!isCurrent()) {
                    return;
                }
                setConnectionState(MultiplayerSession.ConnectionState.CONNECTING);
            }
        }

        @Override
        public void onReconnecting(int attempt, Duration delay) {
            synchronized (commandLock) {
                if (!isCurrent()) {
                    return;
                }
                String message = "The multiplayer connection was interrupted.";
                PendingSubmission pending = transitionAfterConnectionFailure(
                    MultiplayerSession.ConnectionState.RECONNECTING);
                completePending(
                    pending,
                    CommandCompletionStatus.CONNECTION_FAILED,
                    ErrorResponse.of("CONNECTION_LOST", message),
                    session.latestState().orElse(null)
                );
                notifyConnectionState(MultiplayerSession.ConnectionState.RECONNECTING);
                notifyReconnecting(attempt, delay);
            }
        }

        @Override
        public void onDisconnected(MatchWebSocketClient.DisconnectReason reason) {
            synchronized (commandLock) {
                if (!isCurrent()) {
                    return;
                }
                boolean terminal = session.latestState()
                    .map(state -> isTerminal(state.status()))
                    .orElse(false);
                PendingSubmission pending = null;
                if (!(reason == MatchWebSocketClient.DisconnectReason.EXPLICIT
                    && terminal)) {
                    pending = transitionAfterConnectionFailure(
                        MultiplayerSession.ConnectionState.DISCONNECTED);
                } else {
                    session.setConnectionState(
                        MultiplayerSession.ConnectionState.DISCONNECTED);
                }
                String message = "The multiplayer connection was closed.";
                completePending(
                    pending,
                    CommandCompletionStatus.CONNECTION_FAILED,
                    ErrorResponse.of("CONNECTION_CLOSED", message),
                    session.latestState().orElse(null)
                );
                notifyConnectionState(MultiplayerSession.ConnectionState.DISCONNECTED);
                notifyDisconnected(reason);
            }
        }

        @Override
        public void onMessage(SocketMessage message) {
            synchronized (commandLock) {
                if (!isCurrent()) {
                    return;
                }
                switch (message.type()) {
                    case MATCH_JOINED, MATCH_STATE -> handleStateMessage(message, false);
                    case COMMAND_REJECTED -> handleCommandRejected(message);
                    case PLAYER_CONNECTED, PLAYER_DISCONNECTED -> handlePlayerEvent(message);
                    case MATCH_ENDED -> handleStateMessage(message, true);
                    case PONG -> handlePong(message);
                    case ERROR -> handleServerError(message);
                    case JOIN_MATCH, SUBMIT_ACTION, PING -> {
                        session.recordError(
                            "UNSUPPORTED_MESSAGE_TYPE",
                            "The server sent an unsupported multiplayer message."
                        );
                        notifyError(
                            "UNSUPPORTED_MESSAGE_TYPE",
                            "The server sent an unsupported multiplayer message.",
                            null
                        );
                    }
                }
            }
        }

        @Override
        public void onError(MatchWebSocketClient.ConnectionError error) {
            synchronized (commandLock) {
                if (!isCurrent()) {
                    return;
                }
                session.recordError(error.code(), error.userMessage());
                notifyError(error.code(), error.userMessage(), error.cause());
            }
        }

        private boolean isCurrent() {
            return !closed.get() && generation == connectionGeneration;
        }
    }

    private record PendingSubmission(
        ActionCommand command,
        CompletableFuture<CommandOutcome> completion
    ) {
    }
}
