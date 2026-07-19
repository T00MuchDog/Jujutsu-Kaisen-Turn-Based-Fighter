package com.jjktbf.multiplayer.protocol;

/** Binds a host decision to the exact pending request shown in the UI. */
public record ChallengeDecisionRequest(
    String expectedRequestId,
    String expectedRequesterId,
    long expectedRequestedAt
) {
    public static ChallengeDecisionRequest forChallenge(ChallengeSummary challenge) {
        if (challenge == null) {
            throw new IllegalArgumentException("Challenge does not identify a join request");
        }
        if (challenge.joinRequestId() != null
            && challenge.requestedPlayerId() != null
            && challenge.requestedAt() != null) {
            return new ChallengeDecisionRequest(
                challenge.joinRequestId(),
                challenge.requestedPlayerId(),
                challenge.requestedAt()
            );
        }
        if (challenge.acceptedJoinRequestId() == null) {
            throw new IllegalArgumentException("Challenge does not have a pending request");
        }
        return new ChallengeDecisionRequest(
            challenge.acceptedJoinRequestId(),
            "",
            0L
        );
    }
}
