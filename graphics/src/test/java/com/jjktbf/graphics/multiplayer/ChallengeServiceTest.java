package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.GuestCreateRequest;
import com.jjktbf.multiplayer.protocol.GuestCreateResponse;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChallengeServiceTest {
    private MultiplayerSession session;
    private FakeApi api;
    private ChallengeService service;

    @BeforeEach
    void setUp() {
        session = new MultiplayerSession();
        session.setGuestCredentials(MultiplayerTestData.credentials("private-token"));
        api = new FakeApi();
        service = new ChallengeService(api, session);
    }

    @Test
    void requestsAndRejectsJoinWithExplicitSessionOwnership() throws Exception {
        ChallengeSummary pending = summary(
            ChallengeStatus.OPEN,
            MultiplayerTestData.PLAYER_ID,
            "character-two",
            1_100L,
            null
        );
        ChallengeSummary rejected = summary(ChallengeStatus.OPEN, null, null, null, null);
        api.joinResult = CompletableFuture.completedFuture(pending);
        api.rejectResult = CompletableFuture.completedFuture(rejected);

        assertEquals(pending,
            service.requestJoin(pending.challengeId(), "character-two")
                .get(5, TimeUnit.SECONDS));
        assertEquals("private-token", api.lastToken);
        assertEquals(pending.challengeId(), api.lastChallengeId);
        assertEquals(ChallengeAcceptRequest.standard("character-two"), api.lastJoinRequest);
        service.rememberChallenge(pending);
        assertEquals(pending, service.currentChallenge().orElseThrow());

        assertEquals(rejected,
            service.rejectJoinRequest(pending).get(5, TimeUnit.SECONDS));
        assertEquals(pending, service.currentChallenge().orElseThrow());
    }

    @Test
    void hostAcceptRecoversCommittedMatchAfterResponseFailure() throws Exception {
        MatchSetup setup = MultiplayerTestData.setup(7L);
        ChallengeSummary pending = summary(
            ChallengeStatus.OPEN,
            MultiplayerTestData.PLAYER_ID,
            "character-two",
            1_100L,
            null
        );
        ChallengeSummary accepted = summary(
            ChallengeStatus.ACCEPTED, null, null, null, setup.matchId());
        api.acceptResult = CompletableFuture.failedFuture(
            new ApiClientException(
                ApiClientException.Kind.TIMEOUT,
                -1,
                "REQUEST_TIMEOUT",
                "response lost",
                null
            ));
        api.challengeResult = CompletableFuture.completedFuture(accepted);
        api.acceptRecoveryResult = CompletableFuture.completedFuture(setup);

        MatchSetup result = service.acceptChallenge(pending)
            .get(5, TimeUnit.SECONDS);

        assertSame(setup, result);
        assertEquals(2, api.acceptCalls);
    }

    @Test
    void joinRequestRecoversAfterAmbiguousResponseFailure() throws Exception {
        ChallengeSummary pending = summary(
            ChallengeStatus.OPEN,
            MultiplayerTestData.PLAYER_ID,
            "character-two",
            1_100L,
            null
        );
        api.joinResult = CompletableFuture.failedFuture(new ApiClientException(
            ApiClientException.Kind.TIMEOUT,
            -1,
            "REQUEST_TIMEOUT",
            "response lost",
            null
        ));
        api.challengeResult = CompletableFuture.completedFuture(pending);

        assertEquals(pending, service.requestJoin(pending.challengeId(), "character-two")
            .get(5, TimeUnit.SECONDS));
    }

    @Test
    void joinRecoveryWaitsForACommitThatWasNotImmediatelyVisible() throws Exception {
        ChallengeSummary open = summary(ChallengeStatus.OPEN, null, null, null, null);
        ChallengeSummary pending = summary(
            ChallengeStatus.OPEN,
            MultiplayerTestData.PLAYER_ID,
            "character-two",
            1_100L,
            null
        );
        api.joinResult = CompletableFuture.failedFuture(timeout());
        api.challengeResults.add(CompletableFuture.completedFuture(open));
        api.challengeResults.add(CompletableFuture.completedFuture(open));
        api.challengeResults.add(CompletableFuture.completedFuture(pending));

        assertEquals(pending, service.requestJoin(pending.challengeId(), "character-two")
            .get(5, TimeUnit.SECONDS));
        assertEquals(3, api.challengeCalls);
    }

    @Test
    void joinRecoveryRecognizesAnAlreadyAcceptedRequester() throws Exception {
        MatchSetup setup = MultiplayerTestData.setup(9L);
        ChallengeSummary accepted = summary(
            ChallengeStatus.ACCEPTED, null, null, null, setup.matchId());
        api.joinResult = CompletableFuture.failedFuture(timeout());
        api.challengeResult = CompletableFuture.completedFuture(accepted);
        api.matchResult = CompletableFuture.completedFuture(setup);

        assertEquals(accepted, service.requestJoin(
            accepted.challengeId(), "character-two").get(5, TimeUnit.SECONDS));
    }

    private static ApiClientException timeout() {
        return new ApiClientException(
            ApiClientException.Kind.TIMEOUT,
            -1,
            "REQUEST_TIMEOUT",
            "response lost",
            null
        );
    }

    private static ChallengeSummary summary(
        ChallengeStatus status,
        String requestedPlayerId,
        String requestedCharacterId,
        Long requestedAt,
        String matchId
    ) {
        return new ChallengeSummary(
            "33333333-3333-3333-3333-333333333333",
            "44444444-4444-4444-4444-444444444444",
            "Host Guest",
            "character-one",
            "Host Fighter",
            status,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET,
            1_000L,
            2_000L,
            requestedPlayerId == null ? null : "request-one",
            requestedPlayerId,
            requestedCharacterId,
            requestedAt,
            status == ChallengeStatus.ACCEPTED ? "request-one" : null,
            matchId
        );
    }

    private static final class FakeApi implements MultiplayerApi {
        private CompletableFuture<ChallengeSummary> joinResult = unexpected();
        private CompletableFuture<MatchSetup> acceptResult = unexpected();
        private CompletableFuture<MatchSetup> acceptRecoveryResult = unexpected();
        private CompletableFuture<ChallengeSummary> rejectResult = unexpected();
        private CompletableFuture<ChallengeSummary> challengeResult = unexpected();
        private CompletableFuture<MatchSetup> matchResult = unexpected();
        private final Queue<CompletableFuture<ChallengeSummary>> challengeResults =
            new ArrayDeque<>();
        private int acceptCalls;
        private int challengeCalls;
        private String lastToken;
        private String lastChallengeId;
        private ChallengeAcceptRequest lastJoinRequest;

        @Override
        public CompletableFuture<GuestCreateResponse> createGuest(GuestCreateRequest request) {
            return unexpected();
        }

        @Override
        public CompletableFuture<SessionIdentity> getSession(String token) {
            return unexpected();
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
            challengeCalls++;
            return challengeResults.isEmpty() ? challengeResult : challengeResults.remove();
        }

        @Override
        public CompletableFuture<ChallengeSummary> getRequestedChallenge(String token) {
            return challengeResult;
        }

        @Override
        public CompletableFuture<ChallengeSummary> getHostedChallenge(String token) {
            return challengeResult;
        }

        @Override
        public CompletableFuture<ChallengeSummary> requestJoin(
            String token,
            String challengeId,
            ChallengeAcceptRequest request
        ) {
            lastToken = token;
            lastChallengeId = challengeId;
            lastJoinRequest = request;
            return joinResult;
        }

        @Override
        public CompletableFuture<MatchSetup> acceptChallenge(
            String token,
            String challengeId,
            ChallengeDecisionRequest request
        ) {
            acceptCalls++;
            return acceptCalls == 1 ? acceptResult : acceptRecoveryResult;
        }

        @Override
        public CompletableFuture<ChallengeSummary> rejectJoinRequest(
            String token,
            String challengeId,
            ChallengeDecisionRequest request
        ) {
            return rejectResult;
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
            return matchResult;
        }

        private static <T> CompletableFuture<T> unexpected() {
            return CompletableFuture.failedFuture(new AssertionError("Unexpected API call"));
        }
    }
}
