package com.jjktbf.server.match;

import com.jjktbf.multiplayer.engine.HeadlessBattleSession;
import com.jjktbf.multiplayer.engine.MatchParticipant;
import com.jjktbf.multiplayer.protocol.ActionCommand;
import com.jjktbf.multiplayer.protocol.CommandResult;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.server.challenge.AcceptedMatchParticipant;
import com.jjktbf.server.challenge.AcceptedMatchSetup;
import com.jjktbf.server.config.ServerConfig;
import com.jjktbf.server.db.Database;
import com.jjktbf.server.service.ServiceErrorCode;
import com.jjktbf.server.service.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns authoritative in-memory matches, connections, timers, and persistence. */
public final class MatchManager implements AutoCloseable {
    public static final Duration DEFAULT_COMPLETED_MATCH_RETENTION = Duration.ofMinutes(1);

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchManager.class);
    private static final String DISCONNECT_TIMEOUT_REASON = "DISCONNECT_TIMEOUT";
    private static final String ABANDONED_REASON = "BOTH_PLAYERS_DISCONNECTED";

    private final ConcurrentHashMap<String, ActiveMatch> matches = new ConcurrentHashMap<>();
    private final MatchPersistenceRepository repository;
    private final Duration disconnectGracePeriod;
    private final Duration completedMatchRetention;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MatchManager(Database database, ServerConfig config) {
        this(
            database,
            Duration.ofSeconds(Objects.requireNonNull(config, "config")
                .disconnectTimeoutSeconds()),
            DEFAULT_COMPLETED_MATCH_RETENTION,
            Clock.systemUTC()
        );
    }

    public MatchManager(
        Database database,
        Duration disconnectGracePeriod,
        Duration completedMatchRetention,
        Clock clock
    ) {
        this(
            database,
            disconnectGracePeriod,
            completedMatchRetention,
            clock,
            newScheduler()
        );
    }

    /** Constructor with an owned injectable scheduler for deterministic tests. */
    public MatchManager(
        Database database,
        Duration disconnectGracePeriod,
        Duration completedMatchRetention,
        Clock clock,
        ScheduledExecutorService scheduler
    ) {
        this.repository = new MatchPersistenceRepository(
            Objects.requireNonNull(database, "database"));
        this.disconnectGracePeriod = requirePositive(
            disconnectGracePeriod, "disconnectGracePeriod");
        this.completedMatchRetention = requireNonNegative(
            completedMatchRetention, "completedMatchRetention");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Creates and stores one seeded authoritative session. Repeated identical setup is safe. */
    public MatchState createMatch(AcceptedMatchSetup setup) {
        ensureOpen();
        validateSetup(setup);

        ActiveMatch current = matches.get(setup.matchId());
        if (current != null) {
            return existingState(current, setup);
        }

        MatchParticipant playerOne = participant(setup.playerOne());
        MatchParticipant playerTwo = participant(setup.playerTwo());
        ActiveMatch candidate = new ActiveMatch(
            setup,
            new HeadlessBattleSession(
                setup.matchId(),
                playerOne,
                playerTwo,
                setup.serverSeed(),
                clock
            )
        );
        ActiveMatch existing = matches.putIfAbsent(setup.matchId(), candidate);
        if (existing != null) {
            return existingState(existing, setup);
        }
        if (closed.get()) {
            matches.remove(setup.matchId(), candidate);
            throw new IllegalStateException("MatchManager is closed");
        }

        LOGGER.info(
            "Match created matchId={} playerOneId={} playerTwoId={}",
            setup.matchId(),
            setup.playerOne().playerId(),
            setup.playerTwo().playerId()
        );
        return candidate.session.snapshot();
    }

    public MatchSetup joinMatch(
        SessionIdentity identity,
        String matchId,
        String gameVersion,
        int protocolVersion,
        String ruleset,
        MatchConnection connection
    ) {
        ensureOpen();
        requireIdentity(identity);
        Objects.requireNonNull(connection, "connection");
        String connectionId = requireConnectionId(connection);
        if (!isOpen(connection)) {
            throw new ServiceException(
                ServiceErrorCode.MATCH_NOT_READY,
                "The match connection is not open."
            );
        }

        ActiveMatch match = requireMatch(matchId);
        synchronized (match) {
            ensureOpen();
            ActiveMatch.ActiveParticipant participant = requireParticipant(
                match, identity.playerId());
            validateCompatibility(match.setup, gameVersion, protocolVersion, ruleset);
            validateConnectionIdIsUnique(match, connection, connectionId);

            MatchConnection replaced = participant.connection;
            long now = clock.millis();
            repository.recordJoined(
                matchId,
                identity.playerId(),
                statusAfterJoin(match, participant),
                now
            );
            cancelDisconnect(participant);
            participant.connection = connection;
            participant.disconnectDeadline = null;

            match.session.setConnected(identity.playerId(), true, null);

            if (replaced != null && replaced != connection) {
                safeClose(replaced);
            }

            MatchState participantState = match.session.snapshotFor(identity.playerId());
            MatchSetup result = toMatchSetup(match, participant, participantState);
            sendIfOpen(
                connection,
                SocketMessage.matchJoined(
                    matchId,
                    participant.accepted.playerId(),
                    participant.accepted.displayName(),
                    participant.accepted.side(),
                    participantState
                )
            );

            ActiveMatch.ActiveParticipant opponent = opponent(match, participant);
            if (opponent.connection != null && isOpen(opponent.connection)) {
                sendIfOpen(
                    opponent.connection,
                    SocketMessage.playerConnected(
                        matchId,
                        participant.accepted.playerId(),
                        participant.accepted.displayName(),
                        participant.accepted.side()
                    )
                );
                sendIfOpen(
                    opponent.connection,
                    SocketMessage.matchState(
                        match.session.snapshotFor(opponent.accepted.playerId()))
                );
            }

            LOGGER.info(
                "Match player connected matchId={} playerId={} connectionId={}",
                matchId,
                participant.accepted.playerId(),
                connectionId
            );
            return result;
        }
    }

    public CommandResult submitAction(
        SessionIdentity identity,
        String matchId,
        ActionCommand command
    ) {
        requireIdentity(identity);
        return submitAction(identity.playerId(), matchId, null, command);
    }

    /** Applies a command only if it originated from the participant's current socket. */
    public CommandResult submitAction(
        SessionIdentity identity,
        String matchId,
        String connectionId,
        ActionCommand command
    ) {
        requireIdentity(identity);
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId cannot be blank");
        }
        return submitAction(identity.playerId(), matchId, connectionId, command);
    }

    private CommandResult submitAction(
        String playerId,
        String matchId,
        String expectedConnectionId,
        ActionCommand command
    ) {
        ensureOpen();
        ActiveMatch match = requireMatch(matchId);
        synchronized (match) {
            ensureOpen();
            ActiveMatch.ActiveParticipant participant = requireParticipant(match, playerId);
            MatchConnection sender = participant.connection;
            if (sender == null || !isOpen(sender) || !match.session.isConnected(playerId)) {
                throw new ServiceException(
                    ServiceErrorCode.MATCH_NOT_READY,
                    "The player must join this match on an open connection before submitting."
                );
            }
            if (expectedConnectionId != null
                && !expectedConnectionId.equals(sender.connectionId())) {
                throw new ServiceException(
                    ServiceErrorCode.MATCH_NOT_READY,
                    "This match connection was replaced before the command was processed."
                );
            }

            CommandResult result = match.session.applyCommand(playerId, command);
            if (!result.accepted()) {
                MatchState senderState = match.session.snapshotFor(playerId);
                CommandResult wireResult = CommandResult.rejected(
                    result.commandId(), result.error(), senderState);
                sendIfOpen(
                    sender,
                    SocketMessage.commandRejected(
                        matchId,
                        result.commandId(),
                        result.error(),
                        senderState
                    )
                );
                LOGGER.info(
                    "Match command rejected matchId={} playerId={} code={}",
                    matchId,
                    playerId,
                    result.error() == null ? "UNKNOWN" : result.error().code()
                );
                return wireResult;
            }

            MatchState authoritativeState = result.state();
            broadcastState(match, playerId, result.commandId());
            if (isTerminal(authoritativeState.status())) {
                MatchResultType resultType = authoritativeState.winnerPlayerId() == null
                    ? MatchResultType.DRAW
                    : MatchResultType.VICTORY;
                completeMatch(match, authoritativeState, resultType, true);
            }
            return CommandResult.accepted(
                result.commandId(),
                result.events(),
                match.session.snapshotFor(playerId)
            );
        }
    }

    /** Ignores callbacks from an old socket after a replacement connection has joined. */
    public boolean disconnect(String matchId, String playerId, String connectionId) {
        if (closed.get()) {
            return false;
        }
        ActiveMatch match = matches.get(matchId);
        if (match == null) {
            return false;
        }

        synchronized (match) {
            if (closed.get()) {
                return false;
            }
            ActiveMatch.ActiveParticipant participant = match.participant(playerId);
            if (participant == null
                || participant.connection == null
                || !Objects.equals(
                    participant.connection.connectionId(), connectionId)) {
                return false;
            }

            long now = clock.millis();
            long deadline = Math.addExact(now, disconnectGracePeriod.toMillis());
            repository.recordDisconnected(
                matchId,
                playerId,
                statusAfterDisconnect(match),
                now
            );
            participant.connection = null;
            cancelDisconnect(participant);
            participant.disconnectDeadline = deadline;
            MatchState state = match.session.setConnected(playerId, false, deadline);

            broadcast(
                match,
                SocketMessage.playerDisconnected(
                    matchId,
                    participant.accepted.playerId(),
                    participant.accepted.displayName(),
                    participant.accepted.side(),
                    deadline
                )
            );
            broadcastState(match, null, null);

            if (!isTerminal(state.status())) {
                participant.disconnectTask = scheduler.schedule(
                    () -> expireDisconnect(matchId, playerId, deadline),
                    disconnectGracePeriod.toNanos(),
                    TimeUnit.NANOSECONDS
                );
            }
            LOGGER.info(
                "Match player disconnected matchId={} playerId={} connectionId={} deadline={}",
                matchId,
                playerId,
                connectionId,
                deadline
            );
            return true;
        }
    }

    public MatchSetup getMatchSetup(SessionIdentity identity, String matchId) {
        ensureOpen();
        requireIdentity(identity);
        ActiveMatch match = requireMatch(matchId);
        synchronized (match) {
            ensureOpen();
            ActiveMatch.ActiveParticipant participant = requireParticipant(
                match, identity.playerId());
            return toMatchSetup(
                match, participant, match.session.snapshotFor(identity.playerId()));
        }
    }

    public MatchState getLatestState(SessionIdentity identity, String matchId) {
        ensureOpen();
        requireIdentity(identity);
        ActiveMatch match = requireMatch(matchId);
        synchronized (match) {
            ensureOpen();
            requireParticipant(match, identity.playerId());
            return match.session.snapshotFor(identity.playerId());
        }
    }

    int managedMatchCount() {
        return matches.size();
    }

    private void expireDisconnect(String matchId, String playerId, long expectedDeadline) {
        ActiveMatch match = matches.get(matchId);
        if (match == null || closed.get()) {
            return;
        }

        try {
            synchronized (match) {
                if (closed.get()) {
                    return;
                }
                ActiveMatch.ActiveParticipant participant = match.participant(playerId);
                if (participant == null
                    || !Objects.equals(participant.disconnectDeadline, expectedDeadline)
                    || participant.connection != null
                    || match.session.isConnected(playerId)
                    || match.session.isEnded()) {
                    return;
                }
                participant.disconnectTask = null;

                ActiveMatch.ActiveParticipant opponent = opponent(match, participant);
                boolean opponentConnected = opponent.connection != null
                    && isOpen(opponent.connection)
                    && match.session.isConnected(opponent.accepted.playerId());
                MatchState terminalState;
                MatchResultType resultType;
                if (opponentConnected) {
                    terminalState = match.session.forfeit(
                        participant.accepted.playerId(), DISCONNECT_TIMEOUT_REASON);
                    resultType = MatchResultType.FORFEIT;
                } else {
                    terminalState = match.session.forceEnd(
                        null, MatchStatus.ABANDONED, ABANDONED_REASON);
                    resultType = MatchResultType.ABANDONED;
                }
                completeMatch(match, terminalState, resultType, false);
            }
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Match disconnect expiry failed matchId={} playerId={} error={}",
                matchId,
                playerId,
                exception.getClass().getSimpleName()
            );
        }
    }

    private void completeMatch(
        ActiveMatch match,
        MatchState state,
        MatchResultType resultType,
        boolean stateAlreadyBroadcast
    ) {
        if (match.completionPersisted) {
            return;
        }

        // Persist the terminal result BEFORE telling clients it ended. This
        // guarantees clients never observe a "match over" state that the server
        // has failed to record durably: if persistence fails we retry in the
        // background and only broadcast once it succeeds. The retry path re-enters
        // here with completionBroadcast still false, so the broadcast still runs
        // exactly once — after the row is written.
        long completedAt = clock.millis();
        try {
            repository.recordCompletion(
                match.setup.matchId(),
                state.status(),
                state.winnerPlayerId(),
                resultType,
                state.endReason(),
                completedAt
            );
        } catch (RuntimeException exception) {
            scheduleCompletionRetry(match, state, resultType);
            LOGGER.error(
                "Match completion persistence failed; retry scheduled matchId={} error={}",
                match.setup.matchId(),
                exception.getClass().getSimpleName()
            );
            return;
        }
        match.completionPersisted = true;
        if (match.completionRetryTask != null) {
            match.completionRetryTask.cancel(false);
            match.completionRetryTask = null;
        }

        if (!match.completionBroadcast) {
            if (!stateAlreadyBroadcast) {
                broadcastState(match, null, null);
            }
            broadcastEnded(match);
            match.completionBroadcast = true;
        }
        cancelAllDisconnects(match);
        scheduleCleanup(match);
        LOGGER.info(
            "Match completed matchId={} status={} resultType={} winnerPlayerId={} reason={}",
            match.setup.matchId(),
            state.status(),
            resultType,
            state.winnerPlayerId(),
            state.endReason()
        );
    }

    private void scheduleCompletionRetry(
        ActiveMatch match,
        MatchState state,
        MatchResultType resultType
    ) {
        if (match.completionRetryTask != null || closed.get()) {
            return;
        }
        match.completionRetryTask = scheduler.schedule(
            () -> retryCompletion(match, state, resultType),
            1,
            TimeUnit.SECONDS
        );
    }

    private void retryCompletion(
        ActiveMatch match,
        MatchState state,
        MatchResultType resultType
    ) {
        if (closed.get() || matches.get(match.setup.matchId()) != match) {
            return;
        }
        synchronized (match) {
            match.completionRetryTask = null;
            if (!closed.get() && !match.completionPersisted) {
                completeMatch(match, state, resultType, true);
            }
        }
    }

    private void scheduleCleanup(ActiveMatch match) {
        if (match.cleanupTask != null) {
            return;
        }
        match.cleanupTask = scheduler.schedule(
            () -> releaseCompleted(match),
            completedMatchRetention.toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    private void releaseCompleted(ActiveMatch match) {
        List<MatchConnection> connections = new ArrayList<>();
        synchronized (match) {
            if (!match.session.isEnded() || !matches.remove(match.setup.matchId(), match)) {
                return;
            }
            for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
                cancelDisconnect(participant);
                if (participant.connection != null) {
                    connections.add(participant.connection);
                    participant.connection = null;
                }
            }
        }
        connections.forEach(MatchManager::safeClose);
    }

    private MatchSetup toMatchSetup(
        ActiveMatch match,
        ActiveMatch.ActiveParticipant participant,
        MatchState state
    ) {
        ActiveMatch.ActiveParticipant opponent = opponent(match, participant);
        return new MatchSetup(
            match.setup.matchId(),
            match.setup.challengeId(),
            state.status(),
            participant.accepted.side(),
            participant.accepted.playerId(),
            opponent.accepted.playerId(),
            opponent.accepted.displayName(),
            participant.accepted.characterId(),
            opponent.accepted.characterId(),
            match.setup.gameVersion(),
            match.setup.protocolVersion(),
            match.setup.ruleset(),
            state,
            state.serverTimestamp()
        );
    }

    private static void broadcast(ActiveMatch match, SocketMessage message) {
        for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
            if (participant.connection != null) {
                sendIfOpen(participant.connection, message);
            }
        }
    }

    private static void broadcastState(
        ActiveMatch match,
        String acknowledgedPlayerId,
        String commandId
    ) {
        for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
            if (participant.connection == null) {
                continue;
            }
            MatchState state = match.session.snapshotFor(participant.accepted.playerId());
            String acknowledgement = participant.accepted.playerId().equals(acknowledgedPlayerId)
                ? commandId : null;
            sendIfOpen(
                participant.connection,
                SocketMessage.matchState(state, acknowledgement)
            );
        }
    }

    private static void broadcastEnded(ActiveMatch match) {
        for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
            if (participant.connection != null) {
                sendIfOpen(
                    participant.connection,
                    SocketMessage.matchEnded(
                        match.session.snapshotFor(participant.accepted.playerId()))
                );
            }
        }
    }

    private static void sendIfOpen(MatchConnection connection, SocketMessage message) {
        if (!isOpen(connection)) {
            return;
        }
        try {
            connection.send(message);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Match message send failed connectionId={} error={}",
                safeConnectionId(connection),
                exception.getClass().getSimpleName()
            );
        }
    }

    private static void safeClose(MatchConnection connection) {
        try {
            connection.close();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Match connection close failed connectionId={} error={}",
                safeConnectionId(connection),
                exception.getClass().getSimpleName()
            );
        }
    }

    private static boolean isOpen(MatchConnection connection) {
        try {
            return connection.isOpen();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String safeConnectionId(MatchConnection connection) {
        try {
            return connection.connectionId();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static void validateCompatibility(
        AcceptedMatchSetup setup,
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) {
        if (!Objects.equals(setup.gameVersion(), gameVersion)
            || setup.protocolVersion() != protocolVersion
            || !Objects.equals(setup.ruleset(), ruleset)) {
            throw new ServiceException(
                ServiceErrorCode.INCOMPATIBLE_VERSION,
                "You are running an outdated version of the game. "
                    + "Please download the latest release to play online.");
        }
    }

    private static void validateConnectionIdIsUnique(
        ActiveMatch match,
        MatchConnection connection,
        String connectionId
    ) {
        for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
            MatchConnection current = participant.connection;
            if (current == null || current == connection) {
                continue;
            }
            if (Objects.equals(current.connectionId(), connectionId)) {
                throw new IllegalArgumentException(
                    "connectionId must uniquely identify one connection");
            }
        }
    }

    private static String requireConnectionId(MatchConnection connection) {
        String connectionId = connection.connectionId();
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId cannot be blank");
        }
        return connectionId;
    }

    private ActiveMatch requireMatch(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            throw matchNotFound();
        }
        ActiveMatch match = matches.get(matchId);
        if (match == null) {
            throw matchNotFound();
        }
        return match;
    }

    private static ActiveMatch.ActiveParticipant requireParticipant(
        ActiveMatch match,
        String playerId
    ) {
        ActiveMatch.ActiveParticipant participant = match.participant(playerId);
        if (participant == null) {
            throw new ServiceException(
                ServiceErrorCode.PLAYER_NOT_IN_MATCH,
                "The authenticated player is not a participant in this match."
            );
        }
        return participant;
    }

    private static ActiveMatch.ActiveParticipant opponent(
        ActiveMatch match,
        ActiveMatch.ActiveParticipant participant
    ) {
        for (ActiveMatch.ActiveParticipant candidate : match.participants.values()) {
            if (candidate != participant) {
                return candidate;
            }
        }
        throw new IllegalStateException("An active match must have two participants");
    }

    private static MatchStatus statusAfterJoin(
        ActiveMatch match,
        ActiveMatch.ActiveParticipant participant
    ) {
        if (match.session.isEnded()) {
            return match.session.getStatus();
        }
        ActiveMatch.ActiveParticipant opponent = opponent(match, participant);
        if (!match.session.hasJoined(opponent.accepted.playerId())) {
            return MatchStatus.WAITING;
        }
        return match.session.isConnected(opponent.accepted.playerId())
            ? MatchStatus.ACTIVE : MatchStatus.OPPONENT_DISCONNECTED;
    }

    private static MatchStatus statusAfterDisconnect(ActiveMatch match) {
        if (match.session.isEnded()) {
            return match.session.getStatus();
        }
        boolean allJoined = match.participants.values().stream()
            .allMatch(participant -> match.session.hasJoined(participant.accepted.playerId()));
        return allJoined ? MatchStatus.OPPONENT_DISCONNECTED : MatchStatus.WAITING;
    }

    private static void requireIdentity(SessionIdentity identity) {
        if (identity == null || identity.playerId() == null || identity.playerId().isBlank()) {
            throw new ServiceException(
                ServiceErrorCode.FORBIDDEN,
                "An authenticated guest identity is required."
            );
        }
    }

    private static ServiceException matchNotFound() {
        return new ServiceException(
            ServiceErrorCode.MATCH_NOT_FOUND,
            "The active match does not exist."
        );
    }

    private static MatchParticipant participant(AcceptedMatchParticipant accepted) {
        return new MatchParticipant(
            accepted.playerId(),
            accepted.displayName(),
            accepted.character(),
            accepted.side()
        );
    }

    private static void validateSetup(AcceptedMatchSetup setup) {
        Objects.requireNonNull(setup, "setup");
        if (setup.matchId().isBlank() || setup.challengeId().isBlank()) {
            throw new IllegalArgumentException("Match and challenge IDs cannot be blank");
        }
        if (setup.status() != MatchStatus.WAITING) {
            throw new IllegalArgumentException("A new active match must start in WAITING");
        }
        if (setup.playerOne().side() != PlayerSide.PLAYER_ONE
            || setup.playerTwo().side() != PlayerSide.PLAYER_TWO) {
            throw new IllegalArgumentException("Accepted participants have invalid sides");
        }
        if (setup.playerOne().playerId().equals(setup.playerTwo().playerId())) {
            throw new IllegalArgumentException("Match participant IDs must be unique");
        }
        if (!setup.playerOne().characterId().equals(setup.playerOne().character().getId())
            || !setup.playerTwo().characterId().equals(setup.playerTwo().character().getId())) {
            throw new IllegalArgumentException(
                "Accepted participants must reference their canonical characters");
        }
        if (!ProtocolVersion.isCompatible(
            setup.gameVersion(), setup.protocolVersion(), setup.ruleset())) {
            throw new IllegalArgumentException("Accepted match compatibility is unsupported");
        }
    }

    private static boolean sameSetup(
        AcceptedMatchSetup first,
        AcceptedMatchSetup second
    ) {
        return first.matchId().equals(second.matchId())
            && first.challengeId().equals(second.challengeId())
            && first.status() == second.status()
            && first.serverSeed() == second.serverSeed()
            && first.gameVersion().equals(second.gameVersion())
            && first.protocolVersion() == second.protocolVersion()
            && first.ruleset().equals(second.ruleset())
            && first.createdAt() == second.createdAt()
            && sameParticipant(first.playerOne(), second.playerOne())
            && sameParticipant(first.playerTwo(), second.playerTwo());
    }

    private static boolean sameParticipant(
        AcceptedMatchParticipant first,
        AcceptedMatchParticipant second
    ) {
        return first.playerId().equals(second.playerId())
            && first.displayName().equals(second.displayName())
            && first.side() == second.side()
            && first.characterId().equals(second.characterId());
    }

    private MatchState existingState(ActiveMatch existing, AcceptedMatchSetup setup) {
        synchronized (existing) {
            ensureOpen();
            if (!sameSetup(existing.setup, setup)) {
                throw new IllegalStateException(
                    "A different active match already uses ID " + setup.matchId());
            }
            return existing.session.snapshot();
        }
    }

    private static boolean isTerminal(MatchStatus status) {
        return status == MatchStatus.ENDED || status == MatchStatus.ABANDONED;
    }

    private static void cancelDisconnect(ActiveMatch.ActiveParticipant participant) {
        ScheduledFuture<?> task = participant.disconnectTask;
        participant.disconnectTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private static void cancelAllDisconnects(ActiveMatch match) {
        for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
            cancelDisconnect(participant);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("MatchManager is closed");
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        duration.toNanos();
        return duration;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        duration.toNanos();
        return duration;
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName("jjktbf-match-timer");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        List<MatchConnection> connections = new ArrayList<>();
        for (ActiveMatch match : matches.values()) {
            synchronized (match) {
                cancelAllDisconnects(match);
                if (match.cleanupTask != null) {
                    match.cleanupTask.cancel(false);
                    match.cleanupTask = null;
                }
                if (match.completionRetryTask != null) {
                    match.completionRetryTask.cancel(false);
                    match.completionRetryTask = null;
                }
                for (ActiveMatch.ActiveParticipant participant : match.participants.values()) {
                    if (participant.connection != null) {
                        connections.add(participant.connection);
                        participant.connection = null;
                    }
                }
            }
        }
        matches.clear();
        connections.forEach(MatchManager::safeClose);

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Match timer did not terminate within the shutdown timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
