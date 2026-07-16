package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
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
            token, ChallengeCreateRequest.standard(characterId)))
            .thenApply(challenge -> {
                session.setCurrentChallenge(challenge);
                return challenge;
            });
    }

    public CompletableFuture<ChallengeListResponse> listChallenges() {
        return withToken(api::listChallenges);
    }

    public CompletableFuture<ChallengeSummary> getChallenge(String challengeId) {
        return withToken(token -> api.getChallenge(token, challengeId))
            .thenApply(challenge -> {
                session.setCurrentChallenge(challenge);
                return challenge;
            });
    }

    public CompletableFuture<MatchSetup> acceptChallenge(
        String challengeId,
        String characterId
    ) {
        CompletableFuture<MatchSetup> attempt = withToken(token -> api.acceptChallenge(
            token,
            challengeId,
            ChallengeAcceptRequest.standard(characterId)
        ));
        return attempt.handle((setup, failure) -> {
            if (failure == null) {
                return CompletableFuture.completedFuture(setup);
            }
            return recoverAcceptedMatch(challengeId).handle((recovered, recoveryFailure) -> {
                if (recoveryFailure == null) {
                    return recovered;
                }
                throw new CompletionException(unwrap(failure));
            });
        }).thenCompose(Function.identity()).thenApply(setup -> {
            session.setMatchSetup(setup);
            return setup;
        });
    }

    public Optional<ChallengeSummary> currentChallenge() {
        return session.currentChallenge();
    }

    public CompletableFuture<ChallengeSummary> cancelChallenge(String challengeId) {
        return withToken(token -> api.cancelChallenge(token, challengeId))
            .thenApply(challenge -> {
                session.setCurrentChallenge(challenge);
                return challenge;
            });
    }

    public CompletableFuture<MatchSetup> getMatchSetup(String matchId) {
        return withToken(token -> api.getMatchSetup(token, matchId))
            .thenApply(setup -> {
                session.setMatchSetup(setup);
                return setup;
            });
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

    private CompletableFuture<MatchSetup> recoverAcceptedMatch(String challengeId) {
        return withToken(token -> api.getChallenge(token, challengeId))
            .thenCompose(challenge -> {
                if (challenge.status() != ChallengeStatus.ACCEPTED
                    || challenge.matchId() == null || challenge.matchId().isBlank()) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("Challenge acceptance was not committed"));
                }
                return withToken(token -> api.getMatchSetup(token, challenge.matchId()));
            });
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
