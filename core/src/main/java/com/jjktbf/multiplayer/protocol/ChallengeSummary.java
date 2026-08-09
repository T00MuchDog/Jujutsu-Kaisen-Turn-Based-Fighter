package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.combat.BattleFormat;

import java.util.List;

/**
 * Public challenge listing and host polling representation.
 *
 * <p>Character ids are ordered rosters (one entry for 1v1, two for 2v2). The
 * singular {@code hostCharacterName} is the first fighter's name for compact
 * display; {@code hostCharacterNames} carries every fighter's name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChallengeSummary(
    String challengeId,
    String hostPlayerId,
    String hostDisplayName,
    List<String> hostCharacterIds,
    List<String> hostCharacterNames,
    ChallengeStatus status,
    BattleFormat format,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    long createdAt,
    long expiresAt,
    String joinRequestId,
    String requestedPlayerId,
    List<String> requestedCharacterIds,
    Long requestedAt,
    String acceptedJoinRequestId,
    String matchId
) {
    public ChallengeSummary {
        hostCharacterIds = hostCharacterIds == null ? List.of() : List.copyOf(hostCharacterIds);
        hostCharacterNames = hostCharacterNames == null
            ? List.of() : List.copyOf(hostCharacterNames);
        requestedCharacterIds = requestedCharacterIds == null
            ? List.of() : List.copyOf(requestedCharacterIds);
    }

    /** Convenience: the host's first (primary) fighter id, for 1v1 callers. */
    public String hostCharacterId() {
        return hostCharacterIds.isEmpty() ? null : hostCharacterIds.get(0);
    }

    /** Convenience: the host's first (primary) fighter name, for compact display. */
    public String hostCharacterName() {
        return hostCharacterNames.isEmpty() ? null : hostCharacterNames.get(0);
    }

    /** Convenience: the join requester's first (primary) fighter id. */
    public String requestedCharacterId() {
        return requestedCharacterIds.isEmpty() ? null : requestedCharacterIds.get(0);
    }
}
