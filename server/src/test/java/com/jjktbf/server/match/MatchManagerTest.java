package com.jjktbf.server.match;

import com.jjktbf.multiplayer.protocol.ActionCommand;
import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.CommandResult;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.server.challenge.AcceptedMatchSetup;
import com.jjktbf.server.challenge.ChallengeService;
import com.jjktbf.server.service.ServiceException;
import com.jjktbf.server.support.ServerTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchManagerTest {
    private static final Duration GRACE_PERIOD = Duration.ofMillis(80);
    private static final Duration RETENTION = Duration.ofSeconds(2);

    private ServerTestFixture fixture;
    private SessionIdentity playerOne;
    private SessionIdentity playerTwo;
    private AcceptedMatchSetup accepted;
    private MatchManager manager;
    private MatchPersistenceRepository repository;

    @BeforeEach
    void setUp() {
        fixture = new ServerTestFixture();
        playerOne = fixture.createGuest("Manager Host");
        playerTwo = fixture.createGuest("Manager Guest");
        String firstCharacter = fixture.catalog().characterSummaries().get(0).characterId();
        String secondCharacter = fixture.catalog().characterSummaries().get(1).characterId();
        ChallengeService challenges = fixture.challengeService();
        String challengeId = challenges.createChallenge(
            playerOne, ChallengeCreateRequest.standard(firstCharacter)).challengeId();
        accepted = challenges.acceptChallenge(
            playerTwo,
            challengeId,
            ChallengeAcceptRequest.standard(secondCharacter)
        );
        repository = new MatchPersistenceRepository(fixture.database());
        manager = new MatchManager(
            fixture.database(), GRACE_PERIOD, RETENTION, fixture.clock());
        manager.createMatch(accepted);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        fixture.close();
    }

    @Test
    void createsWaitingMatchWithCanonicalPlayersSidesAndCharacters() {
        MatchState state = manager.getLatestState(playerOne, accepted.matchId());

        assertEquals(MatchStatus.WAITING, state.status());
        assertEquals(0, state.stateVersion());
        assertEquals(2, state.players().size());
        assertEquals(playerOne.playerId(), state.players().get(0).playerId());
        assertEquals(PlayerSide.PLAYER_ONE, state.players().get(0).side());
        assertEquals(
            accepted.playerOne().characterId(), state.players().get(0).character().characterId());
        assertEquals(playerTwo.playerId(), state.players().get(1).playerId());
        assertEquals(PlayerSide.PLAYER_TWO, state.players().get(1).side());
        assertEquals(
            accepted.playerTwo().characterId(), state.players().get(1).character().characterId());
        assertFalse(state.players().get(0).connected());
        assertFalse(state.players().get(1).connected());

        assertEquals(state, manager.createMatch(accepted));
        assertEquals(1, manager.managedMatchCount());
    }

    @Test
    void joinsBothPlayersWithCompleteNotificationsAndPersistsActiveStatus() {
        FakeMatchConnection first = new FakeMatchConnection("first-connection");
        FakeMatchConnection second = new FakeMatchConnection("second-connection");

        MatchSetup firstJoin = manager.joinMatch(playerOne, accepted.matchId(), first);
        assertEquals(PlayerSide.PLAYER_ONE, firstJoin.playerSide());
        assertEquals(MatchStatus.WAITING, firstJoin.status());
        assertEquals(1, firstJoin.state().stateVersion());
        assertComplete(firstJoin.state());
        SocketMessage firstJoined = first.only(MessageType.MATCH_JOINED);
        assertEquals(firstJoin.state(), firstJoined.state());
        assertEquals(playerOne.playerId(), firstJoined.playerId());

        MatchSetup secondJoin = manager.joinMatch(playerTwo, accepted.matchId(), second);
        assertEquals(PlayerSide.PLAYER_TWO, secondJoin.playerSide());
        assertEquals(MatchStatus.ACTIVE, secondJoin.status());
        assertEquals(2, secondJoin.state().stateVersion());
        assertComplete(secondJoin.state());
        assertEquals(secondJoin.state(), second.only(MessageType.MATCH_JOINED).state());
        assertEquals(playerTwo.playerId(), first.only(MessageType.PLAYER_CONNECTED).playerId());
        assertEquals(secondJoin.state(), first.only(MessageType.MATCH_STATE).state());

        MatchPersistenceRepository.StoredMatch stored = repository
            .findMatch(accepted.matchId()).orElseThrow();
        assertEquals(MatchStatus.ACTIVE, stored.status());
        assertEquals(fixture.clock().millis(), stored.startedAt());
        List<MatchPersistenceRepository.StoredParticipant> participants =
            repository.findParticipants(accepted.matchId());
        assertEquals(2, participants.size());
        assertEquals(fixture.clock().millis(), participants.get(0).joinedAt());
        assertEquals(fixture.clock().millis(), participants.get(1).joinedAt());
        assertNull(participants.get(0).disconnectedAt());
        assertNull(participants.get(1).disconnectedAt());
    }

    @Test
    void acceptedActionsBroadcastAndDuplicateOrStaleCommandsRejectOnlySender() {
        JoinedConnections joined = joinBoth();
        joined.clearMessages();
        long initialVersion = manager.getLatestState(playerOne, accepted.matchId()).stateVersion();
        String firstMoveId = accepted.playerOne().character().getKnownMoves().get(0).getId();
        ActionCommand firstCommand = ActionCommand.submitPlan(
            "first-command",
            accepted.matchId(),
            initialVersion,
            List.of(new PlanPlacement(firstMoveId, 1))
        );

        CommandResult firstResult = manager.submitAction(
            playerOne, accepted.matchId(), firstCommand);

        assertTrue(firstResult.accepted());
        assertEquals(initialVersion + 1, firstResult.state().stateVersion());
        SocketMessage ownerUpdate = joined.first.only(MessageType.MATCH_STATE);
        SocketMessage opponentUpdate = joined.second.only(MessageType.MATCH_STATE);
        assertEquals(firstResult.state(), ownerUpdate.state());
        assertEquals("first-command", ownerUpdate.commandId());
        assertNull(opponentUpdate.commandId());
        assertNotEquals(firstResult.state(), opponentUpdate.state());
        assertTrue(opponentUpdate.state().player(PlayerSide.PLAYER_ONE).orElseThrow()
            .planSubmitted());
        assertTrue(opponentUpdate.state().player(PlayerSide.PLAYER_ONE).orElseThrow()
            .character().plan().queuedSegments().isEmpty());

        joined.clearMessages();
        CommandResult duplicate = manager.submitAction(
            playerOne, accepted.matchId(), firstCommand);
        assertFalse(duplicate.accepted());
        assertEquals("DUPLICATE_COMMAND", duplicate.error().code());
        assertEquals(duplicate.state(), joined.first.only(MessageType.COMMAND_REJECTED).state());
        assertTrue(joined.second.messages().isEmpty());

        joined.clearMessages();
        CommandResult stale = manager.submitAction(
            playerTwo,
            accepted.matchId(),
            emptyPlan("stale-command", initialVersion - 1)
        );
        assertFalse(stale.accepted());
        assertEquals("STALE_STATE_VERSION", stale.error().code());
        assertEquals(stale.state(), joined.second.only(MessageType.COMMAND_REJECTED).state());
        assertTrue(joined.first.messages().isEmpty());

        joined.clearMessages();
        CommandResult secondResult = manager.submitAction(
            playerTwo,
            accepted.matchId(),
            emptyPlan("second-command", firstResult.state().stateVersion())
        );
        assertTrue(secondResult.accepted());
        assertEquals(firstResult.state().stateVersion() + 1, secondResult.state().stateVersion());
        assertEquals(secondResult.state(), joined.first.only(MessageType.MATCH_STATE).state());
        assertEquals(secondResult.state(), joined.second.only(MessageType.MATCH_STATE).state());
    }

    @Test
    void concurrentCommandsAreProcessedSequentiallyAndOnlyAcceptedStateIsBroadcast()
        throws Exception {
        JoinedConnections joined = joinBoth();
        joined.clearMessages();
        long version = manager.getLatestState(playerOne, accepted.matchId()).stateVersion();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CommandResult> first = executor.submit(() -> submitWhenReleased(
                ready, start, emptyPlan("concurrent-one", version)));
            Future<CommandResult> second = executor.submit(() -> submitWhenReleased(
                ready, start, emptyPlan("concurrent-two", version)));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<CommandResult> results = List.of(
                first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(CommandResult::accepted).count());
            assertEquals(1, results.stream().filter(result -> !result.accepted()).count());
            CommandResult rejected = results.stream()
                .filter(result -> !result.accepted()).findFirst().orElseThrow();
            assertEquals("STALE_STATE_VERSION", rejected.error().code());
            assertEquals(version + 1,
                manager.getLatestState(playerOne, accepted.matchId()).stateVersion());
            assertEquals(1, joined.first.ofType(MessageType.MATCH_STATE).size());
            assertEquals(1, joined.second.ofType(MessageType.MATCH_STATE).size());
            assertEquals(1, joined.first.ofType(MessageType.COMMAND_REJECTED).size());
            assertTrue(joined.second.ofType(MessageType.COMMAND_REJECTED).isEmpty());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void replacingConnectionMakesOldCloseCallbackHarmless() {
        JoinedConnections joined = joinBoth();
        FakeMatchConnection replacement = new FakeMatchConnection("replacement-connection");

        MatchSetup replacementJoin = manager.joinMatch(
            playerOne, accepted.matchId(), replacement);

        assertFalse(joined.first.isOpen());
        assertEquals(MatchStatus.ACTIVE, replacementJoin.status());
        assertFalse(manager.disconnect(
            accepted.matchId(), playerOne.playerId(), joined.first.connectionId()));
        assertTrue(manager.getLatestState(playerOne, accepted.matchId())
            .player(PlayerSide.PLAYER_ONE).orElseThrow().connected());
        ServiceException staleConnection = assertThrows(ServiceException.class, () ->
            manager.submitAction(
                playerOne,
                accepted.matchId(),
                joined.first.connectionId(),
                emptyPlan("stale-socket-command", replacementJoin.state().stateVersion())
            ));
        assertEquals("MATCH_NOT_READY", staleConnection.code());

        assertTrue(manager.disconnect(
            accepted.matchId(), playerOne.playerId(), replacement.connectionId()));
        MatchState disconnected = manager.getLatestState(playerTwo, accepted.matchId());
        assertEquals(MatchStatus.OPPONENT_DISCONNECTED, disconnected.status());
        assertFalse(disconnected.player(PlayerSide.PLAYER_ONE).orElseThrow().connected());
        MatchPersistenceRepository.StoredParticipant stored = repository
            .findParticipants(accepted.matchId()).stream()
            .filter(participant -> participant.playerId().equals(playerOne.playerId()))
            .findFirst().orElseThrow();
        assertNotNull(stored.disconnectedAt());
    }

    @Test
    void reconnectBeforeGraceExpiryCancelsForfeitAndReceivesLatestState() {
        JoinedConnections joined = joinBoth();
        joined.clearMessages();
        assertTrue(manager.disconnect(
            accepted.matchId(), playerOne.playerId(), joined.first.connectionId()));
        MatchState disconnected = joined.second.only(MessageType.MATCH_STATE).state();
        assertEquals(MatchStatus.OPPONENT_DISCONNECTED, disconnected.status());
        assertNotNull(disconnected.player(PlayerSide.PLAYER_ONE)
            .orElseThrow().disconnectDeadline());

        fixture.clock().advance(Duration.ofMillis(10));
        FakeMatchConnection reconnected = new FakeMatchConnection("reconnected-first");
        MatchSetup resumed = manager.joinMatch(playerOne, accepted.matchId(), reconnected);

        assertEquals(MatchStatus.ACTIVE, resumed.status());
        assertEquals(resumed.state(), reconnected.only(MessageType.MATCH_JOINED).state());
        parkFor(GRACE_PERIOD.plusMillis(60));
        assertTrue(repository.findResult(accepted.matchId()).isEmpty());
        assertEquals(MatchStatus.ACTIVE,
            repository.findMatch(accepted.matchId()).orElseThrow().status());
        MatchPersistenceRepository.StoredParticipant stored = repository
            .findParticipants(accepted.matchId()).stream()
            .filter(participant -> participant.playerId().equals(playerOne.playerId()))
            .findFirst().orElseThrow();
        assertNull(stored.disconnectedAt());
    }

    @Test
    void graceExpiryForfeitsToConnectedOpponentAndPersistsOneResult() {
        JoinedConnections joined = joinBoth();
        joined.clearMessages();

        assertTrue(manager.disconnect(
            accepted.matchId(), playerOne.playerId(), joined.first.connectionId()));
        await(() -> repository.findResult(accepted.matchId()).isPresent());

        MatchPersistenceRepository.StoredResult result = repository
            .findResult(accepted.matchId()).orElseThrow();
        assertEquals(MatchResultType.FORFEIT, result.resultType());
        assertEquals(playerTwo.playerId(), result.winnerPlayerId());
        assertEquals("DISCONNECT_TIMEOUT", result.reason());
        assertEquals(1, repository.countResults(accepted.matchId()));
        MatchPersistenceRepository.StoredMatch stored = repository
            .findMatch(accepted.matchId()).orElseThrow();
        assertEquals(MatchStatus.ENDED, stored.status());
        assertNotNull(stored.endedAt());
        SocketMessage ended = joined.second.only(MessageType.MATCH_ENDED);
        assertEquals(MatchStatus.ENDED, ended.state().status());
        assertEquals(playerTwo.playerId(), ended.state().winnerPlayerId());

        parkFor(GRACE_PERIOD.plusMillis(40));
        assertEquals(1, repository.countResults(accepted.matchId()));
    }

    @Test
    void graceExpiryAbandonsWhenBothPlayersRemainDisconnected() {
        JoinedConnections joined = joinBoth();

        assertTrue(manager.disconnect(
            accepted.matchId(), playerOne.playerId(), joined.first.connectionId()));
        assertTrue(manager.disconnect(
            accepted.matchId(), playerTwo.playerId(), joined.second.connectionId()));
        await(() -> repository.findResult(accepted.matchId()).isPresent());

        MatchPersistenceRepository.StoredResult result = repository
            .findResult(accepted.matchId()).orElseThrow();
        assertEquals(MatchResultType.ABANDONED, result.resultType());
        assertNull(result.winnerPlayerId());
        assertEquals("BOTH_PLAYERS_DISCONNECTED", result.reason());
        assertEquals(MatchStatus.ABANDONED,
            repository.findMatch(accepted.matchId()).orElseThrow().status());
        assertEquals(1, repository.countResults(accepted.matchId()));
    }

    @Test
    void rejectsUnknownMatchesUnauthorizedPlayersAndIncompatibleJoin() {
        SessionIdentity outsider = fixture.createGuest("Manager Outsider");

        ServiceException missing = assertThrows(
            ServiceException.class,
            () -> manager.getMatchSetup(playerOne, "missing-match")
        );
        assertEquals("MATCH_NOT_FOUND", missing.code());
        assertEquals(404, missing.suggestedStatus());

        ServiceException unauthorized = assertThrows(
            ServiceException.class,
            () -> manager.getMatchSetup(outsider, accepted.matchId())
        );
        assertEquals("PLAYER_NOT_IN_MATCH", unauthorized.code());
        assertEquals(403, unauthorized.suggestedStatus());

        FakeMatchConnection connection = new FakeMatchConnection("incompatible");
        ServiceException incompatible = assertThrows(
            ServiceException.class,
            () -> manager.joinMatch(
                playerOne,
                accepted.matchId(),
                "old-version",
                ProtocolVersion.PROTOCOL_VERSION,
                ProtocolVersion.STANDARD_RULESET,
                connection
            )
        );
        assertEquals("INCOMPATIBLE_VERSION", incompatible.code());
        assertTrue(connection.messages().isEmpty());
    }

    @Test
    void completedMatchesAreReleasedAfterRetentionAndCloseReleasesConnections() {
        manager.close();
        manager = new MatchManager(
            fixture.database(),
            Duration.ofMillis(30),
            Duration.ofMillis(30),
            fixture.clock()
        );
        manager.createMatch(accepted);
        JoinedConnections joined = joinBoth();

        assertTrue(manager.disconnect(
            accepted.matchId(), playerOne.playerId(), joined.first.connectionId()));
        await(() -> repository.findResult(accepted.matchId()).isPresent());
        await(() -> manager.managedMatchCount() == 0);
        assertFalse(joined.second.isOpen());

        ServiceException released = assertThrows(
            ServiceException.class,
            () -> manager.getLatestState(playerTwo, accepted.matchId())
        );
        assertEquals("MATCH_NOT_FOUND", released.code());
        assertEquals(1, repository.countResults(accepted.matchId()));
    }

    @Test
    void closeClosesLiveConnectionsAndClearsManagedMatches() {
        JoinedConnections joined = joinBoth();

        manager.close();

        assertFalse(joined.first.isOpen());
        assertFalse(joined.second.isOpen());
        assertEquals(0, manager.managedMatchCount());
        assertThrows(
            IllegalStateException.class,
            () -> manager.getLatestState(playerOne, accepted.matchId()));
    }

    private CommandResult submitWhenReleased(
        CountDownLatch ready,
        CountDownLatch start,
        ActionCommand command
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent command start timed out");
        }
        return manager.submitAction(playerOne, accepted.matchId(), command);
    }

    private JoinedConnections joinBoth() {
        FakeMatchConnection first = new FakeMatchConnection("first-connection");
        FakeMatchConnection second = new FakeMatchConnection("second-connection");
        manager.joinMatch(playerOne, accepted.matchId(), first);
        manager.joinMatch(playerTwo, accepted.matchId(), second);
        return new JoinedConnections(first, second);
    }

    private ActionCommand emptyPlan(String commandId, long stateVersion) {
        return ActionCommand.submitPlan(
            commandId, accepted.matchId(), stateVersion, List.of());
    }

    private static void assertComplete(MatchState state) {
        assertNotNull(state);
        assertEquals(2, state.players().size());
        state.players().forEach(player -> {
            assertNotNull(player.character());
            assertFalse(player.character().knownMoves().isEmpty());
            assertNotNull(player.character().plan());
        });
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous match lifecycle");
    }

    private static void parkFor(Duration duration) {
        LockSupport.parkNanos(duration.toNanos());
    }

    private record JoinedConnections(
        FakeMatchConnection first,
        FakeMatchConnection second
    ) {
        void clearMessages() {
            first.clear();
            second.clear();
        }
    }

    private static final class FakeMatchConnection implements MatchConnection {
        private final String connectionId;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final CopyOnWriteArrayList<SocketMessage> messages =
            new CopyOnWriteArrayList<>();

        private FakeMatchConnection(String connectionId) {
            this.connectionId = connectionId;
        }

        @Override
        public String connectionId() {
            return connectionId;
        }

        @Override
        public void send(SocketMessage message) {
            if (!open.get()) {
                throw new IllegalStateException("Connection is closed");
            }
            messages.add(message);
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            open.set(false);
        }

        List<SocketMessage> messages() {
            return List.copyOf(messages);
        }

        List<SocketMessage> ofType(MessageType type) {
            return messages.stream().filter(message -> message.type() == type).toList();
        }

        SocketMessage only(MessageType type) {
            List<SocketMessage> matching = ofType(type);
            assertEquals(1, matching.size(), "Expected exactly one " + type + " message");
            return matching.get(0);
        }

        void clear() {
            messages.clear();
        }
    }
}
