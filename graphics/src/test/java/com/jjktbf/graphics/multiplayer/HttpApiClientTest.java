package com.jjktbf.graphics.multiplayer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpApiClientTest {
    private final ObjectMapper mapper = NetworkJson.newMapper();
    private HttpServer server;
    private HttpApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        ClientNetworkConfig config = new ClientNetworkConfig(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            ClientNetworkConfig.DEFAULT_WEBSOCKET_URL
        );
        client = new HttpApiClient(config);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsGuestAsynchronouslyWithJson() throws Exception {
        SessionIdentity identity = new SessionIdentity(
            MultiplayerTestData.PLAYER_ID, "Chosen Guest", 5_000L);
        GuestCreateResponse expected = new GuestCreateResponse(identity, "private-token");
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/guests", exchange -> {
            requestBody.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, mapper.writeValueAsBytes(expected));
        });

        GuestCreateResponse response = client.createGuest(
            new GuestCreateRequest("Chosen Guest")).get(5, TimeUnit.SECONDS);

        assertEquals(expected, response);
        assertTrue(requestBody.get().contains("Chosen Guest"));
    }

    @Test
    void sendsBearerAuthAndExposesStructuredHttpError() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        ErrorResponse response = ErrorResponse.of(
            "INVALID_TOKEN", "The guest token is invalid.");
        server.createContext("/api/session", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 401, mapper.writeValueAsBytes(response));
        });

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            client.getSession("private-token").get(5, TimeUnit.SECONDS));
        ApiClientException apiFailure = assertInstanceOf(
            ApiClientException.class, failure.getCause());

        assertEquals("Bearer private-token", authorization.get());
        assertEquals(ApiClientException.Kind.HTTP_ERROR, apiFailure.kind());
        assertEquals(401, apiFailure.status());
        assertEquals("INVALID_TOKEN", apiFailure.code());
        assertEquals("The guest token is invalid.", apiFailure.userMessage());
    }

    @Test
    void distinguishesMalformedSuccessfulResponse() throws Exception {
        server.createContext("/api/session", exchange ->
            respond(exchange, 200, "not-json".getBytes(StandardCharsets.UTF_8)));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            client.getSession("private-token").get(5, TimeUnit.SECONDS));
        ApiClientException apiFailure = assertInstanceOf(
            ApiClientException.class, failure.getCause());

        assertEquals(ApiClientException.Kind.MALFORMED_RESPONSE, apiFailure.kind());
        assertEquals(200, apiFailure.status());
        assertEquals("MALFORMED_RESPONSE", apiFailure.code());
        assertNotNull(apiFailure.getCause());
    }

    @Test
    void closedClientFailsWithoutStartingANetworkRequest() throws Exception {
        client.close();

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
            client.getSession("private-token").get(5, TimeUnit.SECONDS));
        ApiClientException apiFailure = assertInstanceOf(
            ApiClientException.class, failure.getCause());

        assertEquals(ApiClientException.Kind.CLIENT_CLOSED, apiFailure.kind());
        assertEquals("CLIENT_CLOSED", apiFailure.code());
    }

    private static void respond(HttpExchange exchange, int status, byte[] body)
        throws IOException {
        exchange.getResponseHeaders().set(
            "Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
