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

import java.util.concurrent.CompletableFuture;

/** Injectable asynchronous boundary for all multiplayer HTTP routes. */
public interface MultiplayerApi {
    CompletableFuture<GuestCreateResponse> createGuest(GuestCreateRequest request);

    CompletableFuture<SessionIdentity> getSession(String token);

    CompletableFuture<ChallengeSummary> createChallenge(
        String token,
        ChallengeCreateRequest request
    );

    CompletableFuture<ChallengeListResponse> listChallenges(String token);

    CompletableFuture<ChallengeSummary> getChallenge(String token, String challengeId);

    CompletableFuture<ChallengeSummary> getRequestedChallenge(String token);

    CompletableFuture<ChallengeSummary> getHostedChallenge(String token);

    CompletableFuture<ChallengeSummary> requestJoin(
        String token,
        String challengeId,
        ChallengeAcceptRequest request
    );

    CompletableFuture<MatchSetup> acceptChallenge(
        String token,
        String challengeId,
        ChallengeDecisionRequest request
    );

    CompletableFuture<ChallengeSummary> rejectJoinRequest(
        String token,
        String challengeId,
        ChallengeDecisionRequest request
    );

    CompletableFuture<ChallengeSummary> withdrawJoinRequest(
        String token,
        String challengeId,
        ChallengeDecisionRequest request
    );

    CompletableFuture<ChallengeSummary> cancelChallenge(String token, String challengeId);

    CompletableFuture<MatchSetup> getMatchSetup(String token, String matchId);
}
