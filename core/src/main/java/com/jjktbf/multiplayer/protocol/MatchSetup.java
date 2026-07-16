package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** HTTP response used to initialize or resume a participant's match connection. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchSetup(
    String matchId,
    String challengeId,
    MatchStatus status,
    PlayerSide playerSide,
    String playerId,
    String opponentPlayerId,
    String opponentDisplayName,
    String playerCharacterId,
    String opponentCharacterId,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    MatchState state,
    long serverTimestamp
) {
}
