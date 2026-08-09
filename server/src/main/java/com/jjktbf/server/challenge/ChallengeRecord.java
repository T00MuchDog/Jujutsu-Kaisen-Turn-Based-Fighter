package com.jjktbf.server.challenge;

import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;

import java.util.List;

record ChallengeRecord(
    String challengeId,
    String creatorPlayerId,
    String creatorDisplayName,
    ChallengeStatus status,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    BattleFormat format,
    List<String> hostCharacterIds,
    long createdAt,
    long expiresAt,
    String joinRequestId,
    String requestedPlayerId,
    List<String> requestedCharacterIds,
    Long requestedAt,
    String acceptedPlayerId,
    List<String> acceptedCharacterIds,
    Long acceptedAt,
    String acceptedJoinRequestId,
    String matchId
) {
}
