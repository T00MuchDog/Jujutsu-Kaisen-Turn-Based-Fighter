package com.jjktbf.multiplayer.protocol;

import java.util.List;

/** Snapshot of compatible public challenges. */
public record ChallengeListResponse(
    List<ChallengeSummary> challenges,
    long serverTimestamp
) {
    public ChallengeListResponse {
        challenges = challenges == null ? List.of() : List.copyOf(challenges);
    }
}
