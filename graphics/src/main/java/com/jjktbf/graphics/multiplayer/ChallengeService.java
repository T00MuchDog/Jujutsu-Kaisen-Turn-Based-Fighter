package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.MatchSetup;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/** Session-aware asynchronous challenge and match-setup API. */
public final class ChallengeService {
    private final MultiplayerApi api;
    private final MultiplayerSession session;

    public ChallengeService(MultiplayerApi api, MultiplayerSession session) {
        this.api = Objects.requireNonNull(api, "api");
        this.session = Objects.requireNonNull(session, "session");
    }

    public CompletableFuture<ChallengeSummary> createChallenge(String characterId) {
        return withToken(token -> api.createChallenge(
            token, ChallengeCreateRequest.standard(characterId)));
    }

    public CompletableFuture<ChallengeListResponse> listChallenges() {
        return withToken(api::listChallenges);
    }

    public CompletableFuture<ChallengeSummary> getChallenge(String challengeId) {
        return withToken(token -> api.getChallenge(token, challengeId));
    }

    public CompletableFuture<ChallengeSummary> getRequestedChallenge() {
        return withToken(api::getRequestedChallenge);
    }

    public CompletableFuture<ChallengeSummary> getHostedChallenge() {
        return withToken(api::getHostedChallenge);
    }

    public CompletableFuture<ChallengeSummary> requestJoin(
        String challengeId,
        String characterId
    ) {
        CompletableFuture<ChallengeSummary> attempt = withToken(token -> api.requestJoin(
            token,
            challengeId,
            ChallengeAcceptRequest.standard(characterId)
        ));
        return attempt.handle((challenge, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(challenge);
            if (!isAmbiguous(failure)) {
                return CompletableFuture.<ChallengeSummary>failedFuture(unwrap(failure));
            }
            return recoverJoinRequest(challengeId, characterId).handle((recovered, recoveryFailure) -> {
                if (recoveryFailure == null) return recovered;
                throw new CompletionException(unwrap(failure));
            });
        }).thenCompose(Function.identity());
    }

    public CompletableFuture<MatchSetup> acceptChallenge(ChallengeSummary challenge) {
        Objects.requireNonNull(challenge, "challenge");
        String challengeId = challenge.challengeId();
        ChallengeDecisionRequest decision = ChallengeDecisionRequest.forChallenge(challenge);
        CompletableFuture<MatchSetup> attempt = withToken(token ->
            api.acceptChallenge(token, challengeId, decision));
        return attempt.handle((setup, failure) -> {
            if (failure == null) {
                return CompletableFuture.completedFuture(setup);
            }
            if (!isAmbiguous(failure)) {
                return CompletableFuture.<MatchSetup>failedFuture(unwrap(failure));
            }
            return recoverAcceptedMatch(challengeId, decision)
                .handle((recovered, recoveryFailure) -> {
                    if (recoveryFailure == null) {
                        return recovered;
                    }
                    throw new CompletionException(unwrap(failure));
                });
        }).thenCompose(Function.identity());
    }

    public CompletableFuture<ChallengeSummary> rejectJoinRequest(ChallengeSummary challenge) {
        Objects.requireNonNull(challenge, "challenge");
        ChallengeDecisionRequest decision = ChallengeDecisionRequest.forChallenge(challenge);
        return withToken(token -> api.rejectJoinRequest(
            token, challenge.challengeId(), decision));
    }

    public CompletableFuture<ChallengeSummary> withdrawJoinRequest(
        ChallengeSummary challenge
    ) {
        Objects.requireNonNull(challenge, "challenge");
        ChallengeDecisionRequest decision = ChallengeDecisionRequest.forChallenge(challenge);
        return withToken(token -> api.withdrawJoinRequest(
            token, challenge.challengeId(), decision));
    }

    public Optional<ChallengeSummary> currentChallenge() {
        return session.currentChallenge();
    }

    public void rememberChallenge(ChallengeSummary challenge) {
        session.setCurrentChallenge(challenge);
    }

    public CompletableFuture<ChallengeSummary> cancelChallenge(String challengeId) {
        return withToken(token -> api.cancelChallenge(token, challengeId));
    }

    public CompletableFuture<MatchSetup> getMatchSetup(String matchId) {
        return withToken(token -> api.getMatchSetup(token, matchId));
    }

    private <T> CompletableFuture<T> withToken(
        Function<String, CompletableFuture<T>> operation
    ) {
        GuestCredentials credentials = session.guestCredentials().orElse(null);
        if (credentials == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("A guest session is required"));
        }
        try {
            return operation.apply(credentials.token());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<MatchSetup> recoverAcceptedMatch(
        String challengeId,
        ChallengeDecisionRequest decision
    ) {
        return withToken(token -> api.getChallenge(token, challengeId))
            .thenCompose(challenge -> {
                if (challenge.status() != ChallengeStatus.ACCEPTED
                    || !Objects.equals(
                        decision.expectedRequestId(), challenge.acceptedJoinRequestId())) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("Challenge acceptance was not committed"));
                }
                return withToken(token -> api.acceptChallenge(
                    token, challengeId, decision));
            });
    }

    private CompletableFuture<ChallengeSummary> recoverJoinRequest(
        String challengeId,
        String characterId
    ) {
        String playerId = session.guestCredentials()
            .map(credentials -> credentials.identity().playerId())
            .orElse(null);
        return recoverJoinRequest(challengeId, characterId, playerId, 0);
    }

    private CompletableFuture<ChallengeSummary> recoverJoinRequest(
        String challengeId,
        String characterId,
        String playerId,
        int attempt
    ) {
        return withToken(token -> api.getChallenge(token, challengeId))
            .thenCompose(challenge -> {
                if (challenge.status() == ChallengeStatus.OPEN
                    && Objects.equals(playerId, challenge.requestedPlayerId())
                    && Objects.equals(characterId, challenge.requestedCharacterId())) {
                    return CompletableFuture.completedFuture(challenge);
                }
                if (challenge.status() == ChallengeStatus.ACCEPTED
                    && challenge.matchId() != null && !challenge.matchId().isBlank()) {
                    return withToken(token -> api.getMatchSetup(token, challenge.matchId()))
                        .thenApply(setup -> challenge);
                }
                if (attempt < 3 && challenge.status() == ChallengeStatus.OPEN
                    && challenge.requestedPlayerId() == null) {
                    return CompletableFuture.supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(
                                200L, java.util.concurrent.TimeUnit.MILLISECONDS)
                        )
                        .thenCompose(ignored -> recoverJoinRequest(
                            challengeId, characterId, playerId, attempt + 1));
                }
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Join request was not committed"));
            });
    }

    private static boolean isAmbiguous(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof ApiClientException apiFailure
            && apiFailure.kind() != ApiClientException.Kind.HTTP_ERROR
            && apiFailure.kind() != ApiClientException.Kind.CLIENT_CLOSED;
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
}
