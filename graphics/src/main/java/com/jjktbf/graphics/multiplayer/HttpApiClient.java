package com.jjktbf.graphics.multiplayer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.ErrorResponse;
import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.SessionIdentity;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java 17 asynchronous HTTP/JSON client for the multiplayer API. */
public final class HttpApiClient implements MultiplayerApi, AutoCloseable {
    private static final String JSON = "application/json; charset=utf-8";
    private static final String SAFE_HTTP_ERROR = "The server could not complete the request.";

    private final ClientNetworkConfig config;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpApiClient(ClientNetworkConfig config) {
        this(config, NetworkJson.newMapper());
    }

    public HttpApiClient(ClientNetworkConfig config, ObjectMapper mapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.executor = NetworkExecutors.newBoundedDaemonPool(
            "jjktbf-http",
            ClientNetworkConfig.HTTP_EXECUTOR_THREADS,
            ClientNetworkConfig.EXECUTOR_QUEUE_CAPACITY
        );
        this.client = HttpClient.newBuilder()
            .connectTimeout(ClientNetworkConfig.CONNECT_TIMEOUT)
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public CompletableFuture<GuestCreateResponse> createGuest(GuestCreateRequest request) {
        return post("/api/guests", null,
            request == null ? new GuestCreateRequest(null) : request,
            GuestCreateResponse.class);
    }

    public CompletableFuture<GuestCreateResponse> createGuest() {
        return createGuest(new GuestCreateRequest(null));
    }

    @Override
    public CompletableFuture<SessionIdentity> getSession(String token) {
        return get("/api/session", token, SessionIdentity.class);
    }

    @Override
    public CompletableFuture<ChallengeSummary> createChallenge(
        String token,
        ChallengeCreateRequest request
    ) {
        return post("/api/challenges", token, Objects.requireNonNull(request, "request"),
            ChallengeSummary.class);
    }

    @Override
    public CompletableFuture<ChallengeListResponse> listChallenges(String token) {
        return get("/api/challenges", token, ChallengeListResponse.class);
    }

    @Override
    public CompletableFuture<ChallengeSummary> getChallenge(
        String token,
        String challengeId
    ) {
        return get("/api/challenges/" + pathSegment(challengeId), token,
            ChallengeSummary.class);
    }

    @Override
    public CompletableFuture<ChallengeSummary> getRequestedChallenge(String token) {
        return get("/api/challenges/requested", token, ChallengeSummary.class);
    }

    @Override
    public CompletableFuture<ChallengeSummary> getHostedChallenge(String token) {
        return get("/api/challenges/hosted", token, ChallengeSummary.class);
    }

    @Override
    public CompletableFuture<ChallengeSummary> requestJoin(
        String token,
        String challengeId,
        ChallengeAcceptRequest request
    ) {
        return post(
            "/api/challenges/" + pathSegment(challengeId) + "/join",
            token,
            Objects.requireNonNull(request, "request"),
            ChallengeSummary.class
        );
    }

    @Override
    public CompletableFuture<MatchSetup> acceptChallenge(
        String token,
        String challengeId,
        ChallengeDecisionRequest request
    ) {
        return post(
            "/api/challenges/" + pathSegment(challengeId) + "/accept",
            token,
            Objects.requireNonNull(request, "request"),
            MatchSetup.class
        );
    }

    @Override
    public CompletableFuture<ChallengeSummary> rejectJoinRequest(
        String token,
        String challengeId,
        ChallengeDecisionRequest request
    ) {
        return post(
            "/api/challenges/" + pathSegment(challengeId) + "/reject",
            token,
            Objects.requireNonNull(request, "request"),
            ChallengeSummary.class
        );
    }

    @Override
    public CompletableFuture<ChallengeSummary> withdrawJoinRequest(
        String token,
        String challengeId,
        ChallengeDecisionRequest request
    ) {
        return post(
            "/api/challenges/" + pathSegment(challengeId) + "/withdraw",
            token,
            Objects.requireNonNull(request, "request"),
            ChallengeSummary.class
        );
    }

    @Override
    public CompletableFuture<ChallengeSummary> cancelChallenge(
        String token,
        String challengeId
    ) {
        return postWithoutBody(
            "/api/challenges/" + pathSegment(challengeId) + "/cancel",
            token,
            ChallengeSummary.class
        );
    }

    @Override
    public CompletableFuture<MatchSetup> getMatchSetup(String token, String matchId) {
        return get("/api/matches/" + pathSegment(matchId), token, MatchSetup.class);
    }

    private <T> CompletableFuture<T> get(String path, String token, Class<T> type) {
        return execute("GET", path, token, null, type);
    }

    private <T> CompletableFuture<T> post(
        String path,
        String token,
        Object body,
        Class<T> type
    ) {
        return execute("POST", path, token, body, type);
    }

    private <T> CompletableFuture<T> postWithoutBody(
        String path,
        String token,
        Class<T> type
    ) {
        return execute("POST", path, token, NoBody.INSTANCE, type);
    }

    private <T> CompletableFuture<T> execute(
        String method,
        String path,
        String token,
        Object body,
        Class<T> type
    ) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedException(null));
        }

        CompletableFuture<HttpRequest> prepared;
        try {
            prepared = CompletableFuture.supplyAsync(
                () -> buildRequest(method, path, token, body), executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(closed.get()
                ? closedException(exception)
                : unavailable(exception));
        }

        return prepared.thenCompose(request -> {
            if (closed.get()) {
                return CompletableFuture.failedFuture(closedException(null));
            }
            try {
                return client.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(mapTransportFailure(exception));
            }
        }).handle((response, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof ApiClientException apiFailure) {
                    throw apiFailure;
                }
                throw mapTransportFailure(cause);
            }
            return parseResponse(response, type);
        });
    }

    private HttpRequest buildRequest(String method, String path, String token, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
            .timeout(ClientNetworkConfig.REQUEST_TIMEOUT)
            .header("Accept", "application/json");
        if (token != null) {
            if (token.isBlank()) {
                throw new IllegalArgumentException("token must not be blank");
            }
            builder.header("Authorization", "Bearer " + token);
        }

        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        if (body == NoBody.INSTANCE) {
            return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        try {
            String json = mapper.writeValueAsString(body);
            return builder.header("Content-Type", JSON)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        } catch (JsonProcessingException exception) {
            throw new ApiClientException(
                ApiClientException.Kind.MALFORMED_RESPONSE,
                -1,
                "REQUEST_SERIALIZATION_FAILED",
                "The multiplayer request could not be prepared.",
                exception
            );
        }
    }

    private <T> T parseResponse(HttpResponse<String> response, Class<T> type) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw parseHttpError(status, response.body());
        }
        try {
            return mapper.readValue(response.body(), type);
        } catch (JsonProcessingException exception) {
            throw new ApiClientException(
                ApiClientException.Kind.MALFORMED_RESPONSE,
                status,
                "MALFORMED_RESPONSE",
                "The server returned an unreadable response.",
                exception
            );
        }
    }

    private ApiClientException parseHttpError(int status, String body) {
        try {
            ErrorResponse response = mapper.readValue(body, ErrorResponse.class);
            String code = safeCode(response.code(), status);
            String message = safeMessage(response.message(), SAFE_HTTP_ERROR);
            return new ApiClientException(
                ApiClientException.Kind.HTTP_ERROR, status, code, message, null);
        } catch (JsonProcessingException | RuntimeException exception) {
            return new ApiClientException(
                ApiClientException.Kind.HTTP_ERROR,
                status,
                "HTTP_" + status,
                SAFE_HTTP_ERROR,
                exception
            );
        }
    }

    private URI endpoint(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("API path must start with '/'");
        }
        return URI.create(config.httpBaseUri() + path);
    }

    private static String pathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path identifier must not be blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeCode(String code, int status) {
        if (code == null || code.isBlank() || code.length() > 80
            || !code.matches("[A-Z0-9_]+")) {
            return "HTTP_" + status;
        }
        return code;
    }

    private static String safeMessage(String message, String fallback) {
        if (message == null || message.isBlank() || message.length() > 500) {
            return fallback;
        }
        return message;
    }

    private ApiClientException mapTransportFailure(Throwable failure) {
        if (failure instanceof ApiClientException apiFailure) {
            return apiFailure;
        }
        if (closed.get()) {
            return closedException(failure);
        }
        if (failure instanceof HttpTimeoutException) {
            return new ApiClientException(
                ApiClientException.Kind.TIMEOUT,
                -1,
                "REQUEST_TIMEOUT",
                "The multiplayer server took too long to respond.",
                failure
            );
        }
        if (failure instanceof ConnectException || failure instanceof IOException) {
            return unavailable(failure);
        }
        return unavailable(failure);
    }

    private static ApiClientException unavailable(Throwable cause) {
        return new ApiClientException(
            ApiClientException.Kind.UNAVAILABLE,
            -1,
            "SERVER_UNAVAILABLE",
            "The multiplayer server is currently unavailable.",
            cause
        );
    }

    private static ApiClientException closedException(Throwable cause) {
        return new ApiClientException(
            ApiClientException.Kind.CLIENT_CLOSED,
            -1,
            "CLIENT_CLOSED",
            "The multiplayer network client is closed.",
            cause
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private enum NoBody {
        INSTANCE
    }
}
