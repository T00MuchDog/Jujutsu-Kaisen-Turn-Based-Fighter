package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Public challenge listing and host polling representation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChallengeSummary(
    String challengeId,
    String hostPlayerId,
    String hostDisplayName,
    String hostCharacterId,
    String hostCharacterName,
    ChallengeStatus status,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    long createdAt,
    long expiresAt,
    String matchId
) {
}
