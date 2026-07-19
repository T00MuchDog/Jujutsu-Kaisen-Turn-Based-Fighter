package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuestAccountServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesAStoredCredentialAfterSessionValidation() throws Exception {
        GuestCredentials stored = MultiplayerTestData.credentials("stored-token");
        GuestCredentialsStore store = store();
        store.save(stored);
        FakeApi api = new FakeApi();
        api.sessionResult = CompletableFuture.completedFuture(stored.identity());
        MultiplayerSession session = new MultiplayerSession();

        try (GuestAccountService service = new GuestAccountService(api, store, session)) {
            GuestCredentials result = service.ensureGuest().get(5, TimeUnit.SECONDS);

            assertEquals(stored, result);
            assertEquals(0, api.createCalls.get());
            assertEquals(stored, session.guestCredentials().orElseThrow());
            assertEquals(stored, store.load().orElseThrow());
        }
    }

    @Test
    void invalidTokenIsClearedAndReplacedBeforeCompletion() throws Exception {
        GuestCredentials old = MultiplayerTestData.credentials("expired-token");
        GuestCredentials replacement = new GuestCredentials(
            new SessionIdentity(
                "55555555-5555-5555-5555-555555555555", "Guest-9999", 20_000L),
            "replacement-token"
        );
        GuestCredentialsStore store = store();
        store.save(old);
        FakeApi api = new FakeApi();
        api.sessionResult = CompletableFuture.failedFuture(new ApiClientException(
            ApiClientException.Kind.HTTP_ERROR,
            401,
            "INVALID_TOKEN",
            "The guest token is invalid.",
            null
        ));
        api.createResult = CompletableFuture.completedFuture(
            new GuestCreateResponse(replacement.identity(), replacement.token()));

        try (GuestAccountService service = new GuestAccountService(
            api, store, new MultiplayerSession())) {
            GuestCredentials result = service.ensureGuest().get(5, TimeUnit.SECONDS);

            assertEquals(replacement, result);
            assertEquals(1, api.createCalls.get());
            assertEquals(replacement, store.load().orElseThrow());
        }
    }

    @Test
    void unavailableServerDoesNotDestroyStoredCredential() throws Exception {
        GuestCredentials stored = MultiplayerTestData.credentials("keep-token");
        GuestCredentialsStore store = store();
        store.save(stored);
        FakeApi api = new FakeApi();
        ApiClientException unavailable = new ApiClientException(
            ApiClientException.Kind.UNAVAILABLE,
            -1,
            "SERVER_UNAVAILABLE",
            "The multiplayer server is unavailable.",
            new java.io.IOException("offline")
        );
        api.sessionResult = CompletableFuture.failedFuture(unavailable);

        try (GuestAccountService service = new GuestAccountService(
            api, store, new MultiplayerSession())) {
            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                service.ensureGuest().get(5, TimeUnit.SECONDS));

            assertSame(unavailable, failure.getCause());
            assertEquals(0, api.createCalls.get());
            assertEquals(stored, store.load().orElseThrow());
        }
    }

    @Test
    void concurrentEnsureCallsShareOneGuestCreation() throws Exception {
        GuestCredentials replacement = MultiplayerTestData.credentials("new-token");
        FakeApi api = new FakeApi();
        api.createResult = new CompletableFuture<>();
        GuestCredentialsStore store = store();

        try (GuestAccountService service = new GuestAccountService(
            api, store, new MultiplayerSession())) {
            CompletableFuture<GuestCredentials> first = service.ensureGuest();
            CompletableFuture<GuestCredentials> second = service.ensureGuest();
            assertSame(first, second);

            api.createResult.complete(
                new GuestCreateResponse(replacement.identity(), replacement.token()));
            assertEquals(replacement, first.get(5, TimeUnit.SECONDS));
            assertEquals(1, api.createCalls.get());
            assertEquals(replacement, store.load().orElseThrow());
        }
    }

    private GuestCredentialsStore store() {
        return new GuestCredentialsStore(
            temporaryDirectory.resolve("multiplayer/guest-session.json"));
    }

    private static final class FakeApi implements MultiplayerApi {
        private final AtomicInteger createCalls = new AtomicInteger();
        private CompletableFuture<GuestCreateResponse> createResult =
            CompletableFuture.failedFuture(new AssertionError("Unexpected create"));
        private CompletableFuture<SessionIdentity> sessionResult =
            CompletableFuture.failedFuture(new AssertionError("Unexpected validation"));

        @Override
        public CompletableFuture<GuestCreateResponse> createGuest(GuestCreateRequest request) {
            createCalls.incrementAndGet();
            return createResult;
        }

        @Override
        public CompletableFuture<SessionIdentity> getSession(String token) {
            return sessionResult;
        }

        @Override
        public CompletableFuture<ChallengeSummary> createChallenge(
            String token,
            ChallengeCreateRequest request
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeListResponse> listChallenges(String token) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> getChallenge(
            String token,
            String challengeId
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> getRequestedChallenge(String token) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> getHostedChallenge(String token) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> requestJoin(
            String token,
            String challengeId,
            ChallengeAcceptRequest request
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<MatchSetup> acceptChallenge(
            String token,
            String challengeId,
            ChallengeDecisionRequest request
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> rejectJoinRequest(
            String token,
            String challengeId,
            ChallengeDecisionRequest request
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> withdrawJoinRequest(
            String token,
            String challengeId,
            ChallengeDecisionRequest request
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<ChallengeSummary> cancelChallenge(
            String token,
            String challengeId
        ) {
            return unexpected();
        }

        @Override
        public CompletableFuture<MatchSetup> getMatchSetup(String token, String matchId) {
            return unexpected();
        }

        private static <T> CompletableFuture<T> unexpected() {
            return CompletableFuture.failedFuture(new AssertionError("Unexpected API call"));
        }
    }
}
