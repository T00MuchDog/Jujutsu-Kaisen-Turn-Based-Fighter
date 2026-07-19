package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.CommandType;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerMatchServiceTest {
    @Test
    void replacedSocketListenerCannotMutateTheNewMatchGeneration() throws Exception {
        MultiplayerSession session = new MultiplayerSession();
        session.setGuestCredentials(MultiplayerTestData.credentials("token"));
        FakeSocket socket = new FakeSocket();
        MultiplayerMatchService service = new MultiplayerMatchService(session, socket);
        try {
            service.connect(MultiplayerTestData.setup(1)).get(2, TimeUnit.SECONDS);
            MatchWebSocketClient.Listener stale = socket.listener;
            service.connect(MultiplayerTestData.setup(5)).get(2, TimeUnit.SECONDS);

            stale.onMessage(SocketMessage.matchState(MultiplayerTestData.state(99)));

            assertEquals(5, session.latestState().orElseThrow().stateVersion());
            assertEquals(MultiplayerSession.ConnectionState.CONNECTING,
                session.connectionState());
        } finally {
            service.close();
        }
    }

    @Test
    void transportOpenIsNotCommandReadyUntilMatchJoinedSnapshotArrives()
        throws Exception {
        MultiplayerSession session = new MultiplayerSession();
        session.setGuestCredentials(MultiplayerTestData.credentials("token"));
        FakeSocket socket = new FakeSocket();
        MultiplayerMatchService service = new MultiplayerMatchService(session, socket);
        try {
            service.connect(MultiplayerTestData.setup(1)).get(2, TimeUnit.SECONDS);

            assertEquals(MultiplayerSession.ConnectionState.CONNECTING,
                session.connectionState());
            assertEquals(MultiplayerMatchService.SubmissionStatus.NOT_CONNECTED,
                service.submitPlan(List.of()).status());

            socket.listener.onMessage(SocketMessage.matchJoined(
                MultiplayerTestData.MATCH_ID,
                MultiplayerTestData.PLAYER_ID,
                "Guest-1234",
                PlayerSide.PLAYER_ONE,
                MultiplayerTestData.state(2)
            ));
            assertEquals(MultiplayerSession.ConnectionState.CONNECTED,
                session.connectionState());
            assertTrue(service.submitPlan(List.of()).sent());
        } finally {
            service.close();
        }
    }

    @Test
    void constructsVersionedCommandWithoutSpeculativeStateAndBlocksSecondPlan()
        throws Exception {
        Fixture fixture = new Fixture(12);
        try {
            List<PlanPlacement> placements = List.of(new PlanPlacement("move-one", 4));
            MultiplayerMatchService.PlanSubmission first =
                fixture.service.submitPlan(placements);
            MultiplayerMatchService.PlanSubmission second =
                fixture.service.submitPlan(List.of());

            assertTrue(first.sent());
            UUID.fromString(first.commandId());
            assertEquals(MultiplayerMatchService.SubmissionStatus.ALREADY_PENDING,
                second.status());
            assertEquals(12,
                fixture.session.latestState().orElseThrow().stateVersion());

            SocketMessage sent = fixture.socket.sent.get(0);
            assertEquals(first.commandId(), sent.command().commandId());
            assertEquals(MultiplayerTestData.MATCH_ID, sent.command().matchId());
            assertEquals(12, sent.command().expectedStateVersion());
            assertEquals(placements, sent.command().payload().placements());
            assertFalse(first.completion().isDone());

            fixture.socket.listener.onMessage(
                SocketMessage.matchState(MultiplayerTestData.state(13)));
            assertFalse(first.completion().isDone());
            assertTrue(fixture.session.pendingCommand().isPresent());

            fixture.socket.listener.onMessage(SocketMessage.matchState(
                MultiplayerTestData.state(13), first.commandId()));
            MultiplayerMatchService.CommandOutcome outcome =
                first.completion().get(2, TimeUnit.SECONDS);

            assertEquals(
                MultiplayerMatchService.CommandCompletionStatus.AUTHORITATIVE_STATE,
                outcome.status());
            assertEquals(13,
                fixture.session.latestState().orElseThrow().stateVersion());
            assertTrue(fixture.session.pendingCommand().isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void matchingCommandRejectionClearsPendingAndKeepsAuthoritativeState()
        throws Exception {
        Fixture fixture = new Fixture(4);
        try {
            MultiplayerMatchService.PlanSubmission submission =
                fixture.service.submitPlan(List.of());
            ErrorResponse error = ErrorResponse.of(
                "STALE_STATE", "The match state changed.");

            fixture.socket.listener.onMessage(SocketMessage.commandRejected(
                MultiplayerTestData.MATCH_ID,
                submission.commandId(),
                error,
                MultiplayerTestData.state(4)
            ));
            MultiplayerMatchService.CommandOutcome outcome =
                submission.completion().get(2, TimeUnit.SECONDS);

            assertEquals(MultiplayerMatchService.CommandCompletionStatus.REJECTED,
                outcome.status());
            assertEquals(error, outcome.error());
            assertEquals(4,
                fixture.session.latestState().orElseThrow().stateVersion());
            assertTrue(fixture.session.pendingCommand().isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void sendsVersionedReadyNextRoundCommand() throws Exception {
        Fixture fixture = new Fixture(14);
        try {
            MultiplayerMatchService.PlanSubmission submission =
                fixture.service.readyNextRound();

            assertTrue(submission.sent());
            SocketMessage sent = fixture.socket.sent.get(0);
            assertEquals(CommandType.READY_NEXT_ROUND, sent.command().type());
            assertEquals(14, sent.command().expectedStateVersion());
            assertEquals(null, sent.command().payload());
        } finally {
            fixture.close();
        }
    }

    @Test
    void connectionFailureClearsPendingAndReportsReconnectState() throws Exception {
        Fixture fixture = new Fixture(2);
        try {
            MultiplayerMatchService.PlanSubmission submission =
                fixture.service.submitPlan(List.of());

            fixture.socket.listener.onReconnecting(1, Duration.ofSeconds(1));
            MultiplayerMatchService.CommandOutcome outcome =
                submission.completion().get(2, TimeUnit.SECONDS);

            assertEquals(
                MultiplayerMatchService.CommandCompletionStatus.CONNECTION_FAILED,
                outcome.status());
            assertEquals(MultiplayerSession.ConnectionState.RECONNECTING,
                fixture.session.connectionState());
            assertTrue(fixture.session.pendingCommand().isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void disconnectInvalidatesCallbacksClearsMatchAndRemainsReconnectable()
        throws Exception {
        Fixture fixture = new Fixture(2);
        try {
            MatchWebSocketClient.Listener staleListener = fixture.socket.listener;
            MultiplayerMatchService.PlanSubmission pending =
                fixture.service.submitPlan(List.of());

            fixture.service.disconnect();

            assertEquals(
                MultiplayerMatchService.CommandCompletionStatus.CONNECTION_FAILED,
                pending.completion().get(2, TimeUnit.SECONDS).status());
            assertEquals(1, fixture.socket.disconnects);
            assertFalse(fixture.socket.closed);
            assertTrue(fixture.session.matchSetup().isEmpty());
            assertTrue(fixture.session.latestState().isEmpty());
            assertTrue(fixture.session.pendingCommand().isEmpty());
            assertEquals(MultiplayerSession.ConnectionState.DISCONNECTED,
                fixture.session.connectionState());

            staleListener.onMessage(SocketMessage.matchState(MultiplayerTestData.state(99)));
            assertTrue(fixture.session.latestState().isEmpty());

            MatchSetup setup = MultiplayerTestData.setup(7);
            fixture.service.connect(setup).get(2, TimeUnit.SECONDS);
            fixture.socket.listener.onMessage(SocketMessage.matchJoined(
                setup.matchId(),
                setup.playerId(),
                "Guest-1234",
                setup.playerSide(),
                setup.state()
            ));

            assertEquals(7, fixture.session.latestState().orElseThrow().stateVersion());
            assertEquals(MultiplayerSession.ConnectionState.CONNECTED,
                fixture.session.connectionState());
            assertTrue(fixture.service.submitPlan(List.of()).sent());
        } finally {
            fixture.close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MultiplayerSession session = new MultiplayerSession();
        private final FakeSocket socket = new FakeSocket();
        private final MultiplayerMatchService service =
            new MultiplayerMatchService(session, socket);

        private Fixture(long version) throws Exception {
            session.setGuestCredentials(MultiplayerTestData.credentials("token"));
            MatchSetup setup = MultiplayerTestData.setup(version);
            service.connect(setup).get(2, TimeUnit.SECONDS);
            socket.listener.onMessage(SocketMessage.matchJoined(
                setup.matchId(),
                setup.playerId(),
                "Guest-1234",
                setup.playerSide(),
                setup.state()
            ));
            assertEquals(MultiplayerSession.ConnectionState.CONNECTED,
                session.connectionState());
        }

        @Override
        public void close() {
            service.close();
        }
    }

    private static final class FakeSocket implements MatchSocket {
        private final List<SocketMessage> sent = new ArrayList<>();
        private MatchWebSocketClient.Listener listener;
        private boolean closed;
        private int disconnects;

        @Override
        public CompletableFuture<Void> connect(
            String guestToken,
            String matchId,
            MatchWebSocketClient.Listener listener
        ) {
            this.listener = listener;
            listener.onConnecting();
            listener.onConnected();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(SocketMessage message) {
            assertNotNull(message);
            sent.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void disconnect() {
            disconnects++;
            if (listener != null) {
                listener.onDisconnected(MatchWebSocketClient.DisconnectReason.EXPLICIT);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
