package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.ActionCommand;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.SocketMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe, LibGDX-independent state shared by multiplayer client services. */
public final class MultiplayerSession {
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    public enum CommandStartStatus {
        READY,
        NO_MATCH,
        NOT_CONNECTED,
        ALREADY_PENDING,
        MATCH_ENDED
    }

    public record CommandStart(CommandStartStatus status, ActionCommand command) {
        public boolean ready() {
            return status == CommandStartStatus.READY;
        }
    }

    public record StateUpdate(
        boolean applied,
        MatchState previousState,
        MatchState currentState,
        ActionCommand clearedPendingCommand
    ) {
    }

    public record SessionError(String code, String message) {
        public SessionError {
            if (code == null || code.isBlank()) {
                code = "MULTIPLAYER_ERROR";
            }
            if (message == null || message.isBlank()) {
                message = "A multiplayer connection error occurred.";
            }
        }
    }

    public record Snapshot(
        GuestCredentials guestCredentials,
        ChallengeSummary currentChallenge,
        MatchSetup matchSetup,
        MatchState latestState,
        ConnectionState connectionState,
        ActionCommand pendingCommand,
        SocketMessage latestSocketMessage,
        SessionError lastError
    ) {
    }

    private GuestCredentials guestCredentials;
    private ChallengeSummary currentChallenge;
    private MatchSetup matchSetup;
    private MatchState latestState;
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private ActionCommand pendingCommand;
    private SocketMessage latestSocketMessage;
    private SessionError lastError;

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            guestCredentials,
            currentChallenge,
            matchSetup,
            latestState,
            connectionState,
            pendingCommand,
            latestSocketMessage,
            lastError
        );
    }

    public synchronized Optional<GuestCredentials> guestCredentials() {
        return Optional.ofNullable(guestCredentials);
    }

    public synchronized void setGuestCredentials(GuestCredentials credentials) {
        this.guestCredentials = Objects.requireNonNull(credentials, "credentials");
    }

    public synchronized void clearGuestCredentials() {
        guestCredentials = null;
    }

    public synchronized Optional<ChallengeSummary> currentChallenge() {
        return Optional.ofNullable(currentChallenge);
    }

    public synchronized void setCurrentChallenge(ChallengeSummary challenge) {
        currentChallenge = Objects.requireNonNull(challenge, "challenge");
    }

    public synchronized void clearCurrentChallenge() {
        currentChallenge = null;
    }

    public synchronized Optional<MatchSetup> matchSetup() {
        return Optional.ofNullable(matchSetup);
    }

    public synchronized Optional<MatchState> latestState() {
        return Optional.ofNullable(latestState);
    }

    public synchronized void setMatchSetup(MatchSetup setup) {
        Objects.requireNonNull(setup, "setup");
        if (setup.matchId() == null || setup.matchId().isBlank()) {
            throw new IllegalArgumentException("setup matchId must not be blank");
        }
        if (setup.state() != null && !setup.matchId().equals(setup.state().matchId())) {
            throw new IllegalArgumentException("setup state belongs to a different match");
        }
        matchSetup = setup;
        latestState = setup.state();
        pendingCommand = null;
        latestSocketMessage = null;
        lastError = null;
        connectionState = ConnectionState.DISCONNECTED;
    }

    public synchronized void clearMatch() {
        matchSetup = null;
        latestState = null;
        pendingCommand = null;
        latestSocketMessage = null;
        lastError = null;
        connectionState = ConnectionState.DISCONNECTED;
    }

    public synchronized ConnectionState connectionState() {
        return connectionState;
    }

    public synchronized boolean setConnectionState(ConnectionState state) {
        Objects.requireNonNull(state, "state");
        if (connectionState == state) {
            return false;
        }
        connectionState = state;
        return true;
    }

    /** Atomically builds a versioned plan command from the latest authoritative state. */
    public synchronized CommandStart beginPlanCommand(
        String commandId,
        List<PlanPlacement> placements
    ) {
        if (matchSetup == null || latestState == null) {
            return new CommandStart(CommandStartStatus.NO_MATCH, null);
        }
        if (isTerminal(latestState.status())) {
            return new CommandStart(CommandStartStatus.MATCH_ENDED, null);
        }
        if (connectionState != ConnectionState.CONNECTED) {
            return new CommandStart(CommandStartStatus.NOT_CONNECTED, null);
        }
        if (pendingCommand != null) {
            return new CommandStart(CommandStartStatus.ALREADY_PENDING, null);
        }
        ActionCommand command = ActionCommand.submitPlan(
            Objects.requireNonNull(commandId, "commandId"),
            matchSetup.matchId(),
            latestState.stateVersion(),
            placements == null ? List.of() : List.copyOf(placements)
        );
        pendingCommand = command;
        return new CommandStart(CommandStartStatus.READY, command);
    }

    /** Atomically builds a versioned next-round readiness command. */
    public synchronized CommandStart beginReadyNextRoundCommand(String commandId) {
        if (matchSetup == null || latestState == null) {
            return new CommandStart(CommandStartStatus.NO_MATCH, null);
        }
        if (isTerminal(latestState.status())) {
            return new CommandStart(CommandStartStatus.MATCH_ENDED, null);
        }
        if (connectionState != ConnectionState.CONNECTED) {
            return new CommandStart(CommandStartStatus.NOT_CONNECTED, null);
        }
        if (pendingCommand != null) {
            return new CommandStart(CommandStartStatus.ALREADY_PENDING, null);
        }
        ActionCommand command = ActionCommand.readyNextRound(
            Objects.requireNonNull(commandId, "commandId"),
            matchSetup.matchId(),
            latestState.stateVersion()
        );
        pendingCommand = command;
        return new CommandStart(CommandStartStatus.READY, command);
    }

    public synchronized Optional<ActionCommand> pendingCommand() {
        return Optional.ofNullable(pendingCommand);
    }

    public synchronized Optional<ActionCommand> clearPendingCommand() {
        ActionCommand cleared = pendingCommand;
        pendingCommand = null;
        return Optional.ofNullable(cleared);
    }

    public synchronized Optional<ActionCommand> clearPendingCommand(String commandId) {
        if (pendingCommand == null || !pendingCommand.commandId().equals(commandId)) {
            return Optional.empty();
        }
        ActionCommand cleared = pendingCommand;
        pendingCommand = null;
        return Optional.of(cleared);
    }

    /** Applies only complete snapshots for this match and ignores stale generations of state. */
    public synchronized StateUpdate updateAuthoritativeState(MatchState state) {
        Objects.requireNonNull(state, "state");
        if (matchSetup == null || !matchSetup.matchId().equals(state.matchId())) {
            return new StateUpdate(false, latestState, latestState, null);
        }
        MatchState previous = latestState;
        if (previous != null && state.stateVersion() < previous.stateVersion()) {
            return new StateUpdate(false, previous, previous, null);
        }

        latestState = state;
        ActionCommand cleared = null;
        if (pendingCommand != null && isTerminal(state.status())) {
            cleared = pendingCommand;
            pendingCommand = null;
        }
        return new StateUpdate(true, previous, state, cleared);
    }

    public synchronized void recordSocketMessage(SocketMessage message) {
        latestSocketMessage = Objects.requireNonNull(message, "message");
    }

    public synchronized void recordError(String code, String message) {
        lastError = new SessionError(code, message);
    }

    public synchronized void clearError() {
        lastError = null;
    }

    private static boolean isTerminal(MatchStatus status) {
        return status == MatchStatus.ENDED || status == MatchStatus.ABANDONED;
    }
}
