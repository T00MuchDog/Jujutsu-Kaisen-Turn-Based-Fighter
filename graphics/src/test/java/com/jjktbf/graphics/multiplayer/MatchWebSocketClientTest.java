package com.jjktbf.graphics.multiplayer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchWebSocketClientTest {
    @Test
    void assemblesFragmentedTextAndRecordsPong() throws Exception {
        SocketFixture fixture = new SocketFixture();
        try {
            RecordingListener listener = new RecordingListener();
            fixture.client.connect("secret token", MultiplayerTestData.MATCH_ID, listener)
                .get(2, TimeUnit.SECONDS);
            String json = fixture.mapper.writeValueAsString(SocketMessage.pong(42L));
            int split = json.length() / 2;

            fixture.connector.listeners.get(0).onText(
                fixture.connector.sockets.get(0), json.substring(0, split), false);
            fixture.connector.listeners.get(0).onText(
                fixture.connector.sockets.get(0), json.substring(split), true);

            List<SocketMessage> pongs = listener.messages.stream()
                .filter(message -> message.type() == MessageType.PONG)
                .toList();
            assertEquals(1, pongs.size());
            assertEquals(42L, pongs.get(0).heartbeatTimestamp());
            assertEquals(42L, fixture.client.lastPongTimestamp().orElseThrow());
            assertEquals(ClientNetworkConfig.DEFAULT_WEBSOCKET_URL,
                fixture.connector.uris.get(0).toString());
            assertEquals("secret token", fixture.connector.tokens.get(0));
        } finally {
            fixture.close();
        }
    }

    @Test
    void callbacksFromReplacedGenerationCannotDispatchMessages() throws Exception {
        SocketFixture fixture = new SocketFixture();
        try {
            RecordingListener listener = new RecordingListener();
            fixture.client.connect("first", MultiplayerTestData.MATCH_ID, listener)
                .get(2, TimeUnit.SECONDS);
            WebSocket.Listener staleListener = fixture.connector.listeners.get(0);
            FakeWebSocket staleSocket = fixture.connector.sockets.get(0);

            fixture.client.connect("second", MultiplayerTestData.MATCH_ID, listener)
                .get(2, TimeUnit.SECONDS);
            String stalePong = fixture.mapper.writeValueAsString(SocketMessage.pong(1L));
            String currentPong = fixture.mapper.writeValueAsString(SocketMessage.pong(2L));
            staleListener.onText(staleSocket, stalePong, true);
            fixture.connector.listeners.get(1).onText(
                fixture.connector.sockets.get(1), currentPong, true);

            List<SocketMessage> pongs = listener.messages.stream()
                .filter(message -> message.type() == MessageType.PONG)
                .toList();
            assertEquals(1, pongs.size());
            assertEquals(2L, pongs.get(0).heartbeatTimestamp());
        } finally {
            fixture.close();
        }
    }

    @Test
    void transportFailureSchedulesFirstBoundedReconnect() throws Exception {
        SocketFixture fixture = new SocketFixture();
        try {
            RecordingListener listener = new RecordingListener();
            fixture.connector.secondConnection = new CountDownLatch(1);
            fixture.client.connect("token", MultiplayerTestData.MATCH_ID, listener)
                .get(2, TimeUnit.SECONDS);

            fixture.connector.listeners.get(0).onError(
                fixture.connector.sockets.get(0), new IOException("offline"));

            assertTrue(fixture.connector.secondConnection.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1), listener.reconnectAttempts);
            assertEquals(List.of(Duration.ofSeconds(1)), listener.reconnectDelays);
            assertEquals(2, fixture.connector.listeners.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    void connectionCompletesOnlyAfterJoinedMessageIsPublished() throws Exception {
        SocketFixture fixture = new SocketFixture();
        try {
            fixture.connector.autoJoin = false;
            RecordingListener listener = new RecordingListener();

            CompletableFuture<Void> connection = fixture.client.connect(
                "token", MultiplayerTestData.MATCH_ID, listener);
            assertFalse(connection.isDone());

            fixture.connector.sendJoined(0);
            connection.get(2, TimeUnit.SECONDS);

            assertTrue(listener.joinedSeen);
            assertTrue(listener.connectedAfterJoin);
        } finally {
            fixture.close();
        }
    }

    @Test
    void permanentJoinRejectionClosesSocketWithoutRetrying() throws Exception {
        SocketFixture fixture = new SocketFixture();
        try {
            fixture.connector.autoJoin = false;
            RecordingListener listener = new RecordingListener();
            CompletableFuture<Void> connection = fixture.client.connect(
                "token", MultiplayerTestData.MATCH_ID, listener);

            fixture.connector.send(0, SocketMessage.error(ErrorResponse.of(
                "MATCH_NOT_FOUND", "The active match does not exist.")));

            assertThrows(ExecutionException.class,
                () -> connection.get(2, TimeUnit.SECONDS));
            assertTrue(fixture.connector.sockets.get(0).isInputClosed());
            assertEquals(1, fixture.connector.listeners.size());
            assertTrue(listener.reconnectAttempts.isEmpty());
        } finally {
            fixture.close();
        }
    }

    private static final class SocketFixture implements AutoCloseable {
        private final ObjectMapper mapper = NetworkJson.newMapper();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
        private final CapturingConnector connector = new CapturingConnector();
        private final MatchWebSocketClient client = new MatchWebSocketClient(
            new ClientNetworkConfig(
                ClientNetworkConfig.DEFAULT_HTTP_URL,
                ClientNetworkConfig.DEFAULT_WEBSOCKET_URL
            ),
            mapper,
            executor,
            scheduler,
            connector
        );

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class RecordingListener implements MatchWebSocketClient.Listener {
        private final List<SocketMessage> messages = new ArrayList<>();
        private final List<Integer> reconnectAttempts = new ArrayList<>();
        private final List<Duration> reconnectDelays = new ArrayList<>();
        private boolean joinedSeen;
        private boolean connectedAfterJoin;

        @Override
        public void onMessage(SocketMessage message) {
            messages.add(message);
            if (message.type() == MessageType.MATCH_JOINED) {
                joinedSeen = true;
            }
        }

        @Override
        public void onConnected() {
            connectedAfterJoin = joinedSeen;
        }

        @Override
        public void onReconnecting(int attempt, Duration delay) {
            reconnectAttempts.add(attempt);
            reconnectDelays.add(delay);
        }
    }

    private static final class CapturingConnector
        implements MatchWebSocketClient.WebSocketConnector {
        private final List<URI> uris = new ArrayList<>();
        private final List<String> tokens = new ArrayList<>();
        private final List<WebSocket.Listener> listeners = new ArrayList<>();
        private final List<FakeWebSocket> sockets = new ArrayList<>();
        private volatile CountDownLatch secondConnection;
        private volatile boolean autoJoin = true;

        @Override
        public synchronized CompletableFuture<WebSocket> connect(
            URI uri,
            String bearerToken,
            WebSocket.Listener listener
        ) {
            FakeWebSocket socket = new FakeWebSocket();
            uris.add(uri);
            tokens.add(bearerToken);
            listeners.add(listener);
            sockets.add(socket);
            listener.onOpen(socket);
            if (autoJoin) {
                sendJoined(listeners.size() - 1);
            }
            if (listeners.size() == 2 && secondConnection != null) {
                secondConnection.countDown();
            }
            return CompletableFuture.completedFuture(socket);
        }

        private synchronized void sendJoined(int index) {
            send(index, SocketMessage.matchJoined(
                MultiplayerTestData.MATCH_ID,
                MultiplayerTestData.PLAYER_ID,
                "Guest-1234",
                PlayerSide.PLAYER_ONE,
                MultiplayerTestData.state(1)
            ));
        }

        private synchronized void send(int index, SocketMessage message) {
            try {
                String json = NetworkJson.newMapper().writeValueAsString(message);
                listeners.get(index).onText(sockets.get(index), json, true);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private final List<String> sentText = new ArrayList<>();
        private volatile boolean outputClosed;
        private volatile boolean inputClosed;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sentText.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            outputClosed = true;
            inputClosed = true;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return outputClosed;
        }

        @Override
        public boolean isInputClosed() {
            return inputClosed;
        }

        @Override
        public void abort() {
            outputClosed = true;
            inputClosed = true;
        }
    }
}
