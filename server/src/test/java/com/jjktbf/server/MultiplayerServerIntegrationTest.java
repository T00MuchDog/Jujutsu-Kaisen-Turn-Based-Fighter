package com.jjktbf.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.multiplayer.protocol.ActionCommand;
import com.jjktbf.multiplayer.protocol.BattlePhase;
import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.MessageType;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.multiplayer.protocol.SocketMessage;
import com.jjktbf.server.config.ServerConfig;
import com.jjktbf.server.content.ContentCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerServerIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<TestSocket> sockets = new ArrayList<>();
    private MultiplayerServer server;
    private ServerConfig config;
    private ExecutorService clientExecutor;
    private HttpClient client;
    private URI baseUri;

    @BeforeEach
    void setUp() {
        String databaseName = "transport_" + UUID.randomUUID().toString().replace("-", "");
        config = new ServerConfig(
            0,
            "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
            "sa",
            "",
            "integration-test-auth-secret-with-sufficient-length",
            5,
            3,
            3
        );
        server = new MultiplayerServer(config).start();
        clientExecutor = Executors.newFixedThreadPool(4);
        client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .executor(clientExecutor)
            .build();
        baseUri = URI.create("http://127.0.0.1:" + server.port());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (TestSocket socket : sockets) {
            socket.close();
        }
        sockets.clear();
        if (server != null) {
            server.close();
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            assertTrue(clientExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void completeHttpAndWebSocketMatchFlow() throws Exception {
        GuestCreateResponse host = createGuest("Transport Host");
        GuestCreateResponse guest = createGuest("Transport Guest");
        GuestCreateResponse observer = createGuest("Transport Observer");

        ApiResponse sessionResponse = get("/api/session", host.token());
        assertEquals(200, sessionResponse.status());
        SessionIdentity session = read(sessionResponse, SessionIdentity.class);
        assertEquals(host.identity(), session);

        List<String> characterIds = ContentCatalog.load().characterSummaries().stream()
            .map(summary -> summary.characterId())
            .limit(2)
            .toList();
        assertEquals(2, characterIds.size());

        ApiResponse createResponse = postJson(
            "/api/challenges",
            host.token(),
            ChallengeCreateRequest.standard(characterIds.get(0))
        );
        assertEquals(201, createResponse.status());
        ChallengeSummary created = read(createResponse, ChallengeSummary.class);
        assertEquals(ChallengeStatus.OPEN, created.status());
        assertEquals(created, read(
            get("/api/challenges/hosted", host.token()), ChallengeSummary.class));

        ApiResponse listResponse = get("/api/challenges", guest.token());
        assertEquals(200, listResponse.status());
        ChallengeListResponse listed = read(listResponse, ChallengeListResponse.class);
        assertEquals(List.of(created), listed.challenges());

        ApiResponse getResponse = get(
            "/api/challenges/" + created.challengeId(), host.token());
        assertEquals(200, getResponse.status());
        assertEquals(created, read(getResponse, ChallengeSummary.class));

        ApiResponse joinResponse = postJson(
            "/api/challenges/" + created.challengeId() + "/join",
            guest.token(),
            ChallengeAcceptRequest.standard(characterIds.get(1))
        );
        assertEquals(200, joinResponse.status());
        ChallengeSummary pending = read(joinResponse, ChallengeSummary.class);
        assertEquals(ChallengeStatus.OPEN, pending.status());
        assertEquals(guest.identity().playerId(), pending.requestedPlayerId());
        assertEquals(characterIds.get(1), pending.requestedCharacterId());
        assertNotNull(pending.requestedAt());
        assertFalse(joinResponse.body().contains(guest.identity().displayName()));
        assertEquals(pending, read(
            get("/api/challenges/requested", guest.token()), ChallengeSummary.class));

        ChallengeListResponse hiddenPending = read(
            get("/api/challenges", observer.token()), ChallengeListResponse.class);
        assertTrue(hiddenPending.challenges().isEmpty());

        ChallengeDecisionRequest decision = ChallengeDecisionRequest.forChallenge(pending);
        ApiResponse acceptResponse = postJson(
            "/api/challenges/" + created.challengeId() + "/accept",
            host.token(),
            decision
        );
        assertEquals(200, acceptResponse.status());
        MatchSetup hostSetup = read(acceptResponse, MatchSetup.class);
        assertEquals(PlayerSide.PLAYER_ONE, hostSetup.playerSide());
        assertEquals(MatchStatus.WAITING, hostSetup.status());
        assertComplete(hostSetup.state());

        ChallengeSummary accepted = read(
            get("/api/challenges/" + created.challengeId(), host.token()),
            ChallengeSummary.class
        );
        assertEquals(ChallengeStatus.ACCEPTED, accepted.status());
        assertEquals(hostSetup.matchId(), accepted.matchId());
        assertEquals(pending.joinRequestId(), accepted.acceptedJoinRequestId());
        assertNull(accepted.requestedPlayerId());

        MatchSetup guestSetup = read(
            get("/api/matches/" + hostSetup.matchId(), guest.token()),
            MatchSetup.class
        );
        assertEquals(PlayerSide.PLAYER_ONE, hostSetup.playerSide());
        assertEquals(PlayerSide.PLAYER_TWO, guestSetup.playerSide());
        assertEquals(hostSetup.state().stateVersion(), guestSetup.state().stateVersion());
        assertEquals(hostSetup.state().status(), guestSetup.state().status());
        assertEquals(hostSetup.state().players(), guestSetup.state().players());

        MatchSetup retriedHostSetup = read(postJson(
            "/api/challenges/" + created.challengeId() + "/accept",
            host.token(), decision), MatchSetup.class);
        assertEquals(hostSetup.matchId(), retriedHostSetup.matchId());
        assertEquals(PlayerSide.PLAYER_ONE, retriedHostSetup.playerSide());

        ApiResponse secondCreateResponse = postJson(
            "/api/challenges",
            host.token(),
            ChallengeCreateRequest.standard(characterIds.get(0))
        );
        ChallengeSummary secondChallenge = read(
            secondCreateResponse, ChallengeSummary.class);
        ApiResponse secondJoinResponse = postJson(
            "/api/challenges/" + secondChallenge.challengeId() + "/join",
            guest.token(),
            ChallengeAcceptRequest.standard(characterIds.get(1))
        );
        assertEquals(200, secondJoinResponse.status());
        ChallengeSummary secondPending = read(secondJoinResponse, ChallengeSummary.class);
        ApiResponse rejectResponse = postJson(
            "/api/challenges/" + secondChallenge.challengeId() + "/reject",
            host.token(),
            ChallengeDecisionRequest.forChallenge(secondPending)
        );
        assertEquals(200, rejectResponse.status());
        ChallengeSummary rejected = read(rejectResponse, ChallengeSummary.class);
        assertEquals(ChallengeStatus.OPEN, rejected.status());
        assertNull(rejected.requestedPlayerId());
        ApiResponse cancelResponse = postWithoutBody(
            "/api/challenges/" + secondChallenge.challengeId() + "/cancel",
            host.token()
        );
        assertEquals(200, cancelResponse.status());
        assertEquals(ChallengeStatus.CANCELLED,
            read(cancelResponse, ChallengeSummary.class).status());

        TestSocket hostSocket = openSocket(host.token());
        TestSocket guestSocket = openSocket(guest.token());
        hostSocket.send(SocketMessage.joinMatch(guestSetup.matchId()));
        SocketMessage hostJoined = hostSocket.await(MessageType.MATCH_JOINED);
        assertEquals(PlayerSide.PLAYER_ONE, hostJoined.playerSide());
        assertComplete(hostJoined.state());

        guestSocket.send(SocketMessage.joinMatch(guestSetup.matchId()));
        SocketMessage guestJoined = guestSocket.await(MessageType.MATCH_JOINED);
        assertEquals(PlayerSide.PLAYER_TWO, guestJoined.playerSide());
        assertEquals(MatchStatus.ACTIVE, guestJoined.state().status());
        assertComplete(guestJoined.state());

        SocketMessage connected = hostSocket.await(MessageType.PLAYER_CONNECTED);
        assertEquals(guest.identity().playerId(), connected.playerId());
        SocketMessage activeState = hostSocket.await(MessageType.MATCH_STATE);
        assertEquals(guestJoined.state().stateVersion(), activeState.stateVersion());
        assertEquals(guestJoined.state().status(), activeState.state().status());
        assertEquals(guestJoined.state().players(), activeState.state().players());

        long heartbeat = System.currentTimeMillis();
        hostSocket.send(SocketMessage.ping(heartbeat));
        SocketMessage pong = hostSocket.await(MessageType.PONG);
        assertEquals(heartbeat, pong.heartbeatTimestamp());

        long version = activeState.state().stateVersion();
        hostSocket.send(SocketMessage.submitAction(ActionCommand.submitPlan(
            "transport-command-one", guestSetup.matchId(), version, List.of())));
        SocketMessage hostFirstState = hostSocket.await(MessageType.MATCH_STATE);
        SocketMessage guestFirstState = guestSocket.await(MessageType.MATCH_STATE);
        assertEquals(version + 1, hostFirstState.stateVersion());
        assertEquals("transport-command-one", hostFirstState.commandId());
        assertNull(guestFirstState.commandId());
        assertEquals(hostFirstState.stateVersion(), guestFirstState.stateVersion());
        assertEquals(hostFirstState.state().players(), guestFirstState.state().players());
        assertComplete(hostFirstState.state());

        guestSocket.send(SocketMessage.submitAction(ActionCommand.submitPlan(
            "transport-command-two",
            guestSetup.matchId(),
            version,
            List.of()
        )));
        SocketMessage hostSecondState = hostSocket.await(MessageType.MATCH_STATE);
        SocketMessage guestSecondState = guestSocket.await(MessageType.MATCH_STATE);
        assertEquals(hostFirstState.stateVersion() + 1, hostSecondState.stateVersion());
        assertNull(hostSecondState.commandId());
        assertEquals("transport-command-two", guestSecondState.commandId());
        assertEquals(hostSecondState.stateVersion(), guestSecondState.stateVersion());
        assertEquals(hostSecondState.state().players(), guestSecondState.state().players());
        assertComplete(hostSecondState.state());
        assertEquals(BattlePhase.ROUND_END, hostSecondState.state().phase());
        assertEquals(1, hostSecondState.state().roundNumber());

        long roundEndVersion = hostSecondState.stateVersion();
        hostSocket.send(SocketMessage.submitAction(ActionCommand.readyNextRound(
            "transport-ready-one", guestSetup.matchId(), roundEndVersion)));
        SocketMessage hostReadyState = hostSocket.await(MessageType.MATCH_STATE);
        SocketMessage guestObservedReady = guestSocket.await(MessageType.MATCH_STATE);
        assertEquals("transport-ready-one", hostReadyState.commandId());
        assertNull(guestObservedReady.commandId());
        assertEquals(BattlePhase.ROUND_END, hostReadyState.state().phase());

        guestSocket.send(SocketMessage.submitAction(ActionCommand.readyNextRound(
            "transport-ready-two", guestSetup.matchId(), roundEndVersion)));
        SocketMessage hostNextRound = hostSocket.await(MessageType.MATCH_STATE);
        SocketMessage guestNextRound = guestSocket.await(MessageType.MATCH_STATE);
        assertNull(hostNextRound.commandId());
        assertEquals("transport-ready-two", guestNextRound.commandId());
        assertEquals(BattlePhase.PLANNING, hostNextRound.state().phase());
        assertEquals(2, hostNextRound.state().roundNumber());
        assertEquals(hostNextRound.stateVersion(), guestNextRound.stateVersion());
    }

    @Test
    void acceptedRetryRestoresWaitingMatchAfterServerRestart() throws Exception {
        GuestCreateResponse host = createGuest("Restart Host");
        GuestCreateResponse guest = createGuest("Restart Guest");
        List<String> characterIds = ContentCatalog.load().characterSummaries().stream()
            .map(summary -> summary.characterId())
            .limit(2)
            .toList();
        ChallengeSummary challenge = read(postJson(
            "/api/challenges",
            host.token(),
            ChallengeCreateRequest.standard(characterIds.get(0))
        ), ChallengeSummary.class);
        ChallengeSummary pending = read(postJson(
            "/api/challenges/" + challenge.challengeId() + "/join",
            guest.token(),
            ChallengeAcceptRequest.standard(characterIds.get(1))
        ), ChallengeSummary.class);
        ChallengeDecisionRequest decision = ChallengeDecisionRequest.forChallenge(pending);
        MatchSetup original = read(postJson(
            "/api/challenges/" + challenge.challengeId() + "/accept",
            host.token(),
            decision
        ), MatchSetup.class);

        server.close();
        server = new MultiplayerServer(config).start();
        baseUri = URI.create("http://127.0.0.1:" + server.port());

        ChallengeSummary recovered = read(
            get("/api/challenges/requested", guest.token()), ChallengeSummary.class);
        assertEquals(ChallengeStatus.ACCEPTED, recovered.status());

        MatchSetup restored = read(postJson(
            "/api/challenges/" + challenge.challengeId() + "/accept",
            host.token(),
            decision
        ), MatchSetup.class);
        assertEquals(original.matchId(), restored.matchId());

        server.close();
        server = new MultiplayerServer(config).start();
        baseUri = URI.create("http://127.0.0.1:" + server.port());

        TestSocket restoredSocket = openSocket(guest.token());
        restoredSocket.send(SocketMessage.joinMatch(original.matchId()));
        SocketMessage restoredJoin = restoredSocket.await(MessageType.MATCH_JOINED);
        assertEquals(PlayerSide.PLAYER_TWO, restoredJoin.playerSide());
        MatchSetup guestSetup = read(
            get("/api/matches/" + original.matchId(), guest.token()), MatchSetup.class);
        assertEquals(PlayerSide.PLAYER_TWO, guestSetup.playerSide());
    }

    @Test
    void rejectsUnsafeHttpAndWebSocketInputWithoutLeakingInternals() throws Exception {
        ApiResponse missingAuth = get("/api/session", null);
        assertEquals(401, missingAuth.status());
        assertEquals("INVALID_TOKEN", read(missingAuth, ErrorResponse.class).code());

        ApiResponse malformedJson = postRaw(
            "/api/guests", null, "{", "application/json; charset=utf-8");
        assertEquals(400, malformedJson.status());
        ErrorResponse malformedHttpError = read(malformedJson, ErrorResponse.class);
        assertEquals("MALFORMED_REQUEST", malformedHttpError.code());
        assertFalse(malformedHttpError.message().contains("com.jjktbf"));

        GuestCreateResponse guest = createGuest("Transport Errors");
        ApiResponse malformedPath = get("/api/challenges/not-a-uuid", guest.token());
        assertEquals(400, malformedPath.status());
        assertEquals("MALFORMED_REQUEST", read(malformedPath, ErrorResponse.class).code());

        ApiResponse missingRoute = get("/api/does-not-exist", guest.token());
        assertEquals(404, missingRoute.status());
        assertEquals("NOT_FOUND", read(missingRoute, ErrorResponse.class).code());

        String missingId = UUID.randomUUID().toString();
        ApiResponse missingChallenge = get("/api/challenges/" + missingId, guest.token());
        assertEquals(404, missingChallenge.status());
        assertEquals("CHALLENGE_NOT_FOUND",
            read(missingChallenge, ErrorResponse.class).code());
        ApiResponse missingRequested = get("/api/challenges/requested", guest.token());
        assertEquals(404, missingRequested.status());
        assertEquals("CHALLENGE_NOT_FOUND",
            read(missingRequested, ErrorResponse.class).code());
        ApiResponse missingHosted = get("/api/challenges/hosted", guest.token());
        assertEquals(404, missingHosted.status());
        assertEquals("CHALLENGE_NOT_FOUND",
            read(missingHosted, ErrorResponse.class).code());
        ApiResponse missingMatch = get("/api/matches/" + missingId, guest.token());
        assertEquals(404, missingMatch.status());
        assertEquals("MATCH_NOT_FOUND", read(missingMatch, ErrorResponse.class).code());

        TestSocket authenticated = openSocket(guest.token());
        authenticated.sendRaw("{");
        SocketMessage malformedSocket = authenticated.await(MessageType.ERROR);
        assertEquals("MALFORMED_MESSAGE", malformedSocket.error().code());
        authenticated.send(SocketMessage.ping(42L));
        assertEquals(42L,
            authenticated.await(MessageType.PONG).heartbeatTimestamp());

        TestSocket invalid = openSocket("not-a-valid-token");
        SocketMessage invalidToken = invalid.await(MessageType.ERROR);
        assertEquals("INVALID_TOKEN", invalidToken.error().code());
        assertEquals(1008, invalid.awaitClose());
    }

    private GuestCreateResponse createGuest(String displayName) throws Exception {
        ApiResponse response = postJson(
            "/api/guests", null, new GuestCreateRequest(displayName));
        assertEquals(201, response.status());
        return read(response, GuestCreateResponse.class);
    }

    private TestSocket openSocket(String token) throws Exception {
        URI uri = URI.create(
            "ws://127.0.0.1:" + server.port() + "/ws/matches");
        TestSocket socket = TestSocket.open(client, uri, token, mapper);
        sockets.add(socket);
        return socket;
    }

    private ApiResponse get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(TIMEOUT)
            .GET();
        authorize(request, token);
        return send(request.build());
    }

    private ApiResponse postJson(String path, String token, Object body) throws Exception {
        return postRaw(
            path,
            token,
            mapper.writeValueAsString(body),
            "application/json; charset=utf-8"
        );
    }

    private ApiResponse postRaw(
        String path,
        String token,
        String body,
        String contentType
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(TIMEOUT)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        authorize(request, token);
        return send(request.build());
    }

    private ApiResponse postWithoutBody(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody());
        authorize(request, token);
        return send(request.build());
    }

    private ApiResponse send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.toLowerCase().startsWith("application/json"));
        assertTrue(contentType.toLowerCase().contains("charset=utf-8"));
        return new ApiResponse(response.statusCode(), response.body());
    }

    private static void authorize(HttpRequest.Builder request, String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
    }

    private <T> T read(ApiResponse response, Class<T> type) throws JsonProcessingException {
        return mapper.readValue(response.body(), type);
    }

    private static void assertComplete(MatchState state) {
        assertNotNull(state);
        assertEquals(2, state.players().size());
        state.players().forEach(player -> {
            assertNotNull(player.character());
            assertFalse(player.character().knownMoves().isEmpty());
            assertNotNull(player.character().plan());
        });
        if (state.status() != MatchStatus.ENDED) {
            assertNull(state.winnerPlayerId());
        }
    }

    private record ApiResponse(int status, String body) {
    }

    private static final class TestSocket implements WebSocket.Listener, AutoCloseable {
        private final ObjectMapper mapper;
        private final BlockingQueue<SocketMessage> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final CompletableFuture<Throwable> failed = new CompletableFuture<>();
        private final StringBuilder partialText = new StringBuilder();
        private WebSocket webSocket;

        private TestSocket(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        static TestSocket open(
            HttpClient client,
            URI uri,
            String token,
            ObjectMapper mapper
        )
            throws Exception {
            TestSocket listener = new TestSocket(mapper);
            listener.webSocket = client.newWebSocketBuilder()
                .connectTimeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .buildAsync(uri, listener)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
            WebSocket webSocket,
            CharSequence data,
            boolean last
        ) {
            synchronized (partialText) {
                partialText.append(data);
                if (last) {
                    try {
                        messages.add(mapper.readValue(
                            partialText.toString(), SocketMessage.class));
                    } catch (JsonProcessingException exception) {
                        failed.complete(exception);
                    } finally {
                        partialText.setLength(0);
                    }
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
            WebSocket webSocket,
            int statusCode,
            String reason
        ) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failed.complete(error);
        }

        void send(SocketMessage message) throws Exception {
            sendRaw(mapper.writeValueAsString(message));
        }

        void sendRaw(String text) throws Exception {
            webSocket.sendText(text, true)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        SocketMessage await(MessageType type) throws Exception {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (failed.isDone()) {
                    throw new AssertionError("WebSocket listener failed", failed.getNow(null));
                }
                SocketMessage message = messages.poll(50, TimeUnit.MILLISECONDS);
                if (message != null && message.type() == type) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for WebSocket message " + type);
        }

        int awaitClose() throws Exception {
            return closed.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (webSocket == null || webSocket.isOutputClosed()) {
                return;
            }
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test complete")
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                webSocket.abort();
            }
        }
    }
}
