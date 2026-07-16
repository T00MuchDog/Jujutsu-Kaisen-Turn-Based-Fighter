package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.ChallengeStatus;

record ChallengeRecord(
    String challengeId,
    String creatorPlayerId,
    String creatorDisplayName,
    ChallengeStatus status,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    String hostCharacterId,
    long createdAt,
    long expiresAt,
    String acceptedPlayerId,
    String acceptedCharacterId,
    Long acceptedAt,
    String matchId
) {
}
