package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.combat.BattleFormat;

import java.util.List;

/**
 * HTTP response used to initialize or resume a participant's match connection.
 *
 * <p>Character ids are ordered rosters (one entry for 1v1, two for 2v2). The
 * singular accessors return the first (primary) fighter for legacy 1v1 callers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchSetup(
    String matchId,
    String challengeId,
    MatchStatus status,
    PlayerSide playerSide,
    String playerId,
    String opponentPlayerId,
    String opponentDisplayName,
    List<String> playerCharacterIds,
    List<String> opponentCharacterIds,
    BattleFormat format,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    MatchState state,
    long serverTimestamp
) {
    public MatchSetup {
        playerCharacterIds = playerCharacterIds == null
            ? List.of() : List.copyOf(playerCharacterIds);
        opponentCharacterIds = opponentCharacterIds == null
            ? List.of() : List.copyOf(opponentCharacterIds);
    }

    /** Convenience: the local participant's first (primary) fighter id. */
    public String playerCharacterId() {
        return playerCharacterIds.isEmpty() ? null : playerCharacterIds.get(0);
    }

    /** Convenience: the opponent's first (primary) fighter id. */
    public String opponentCharacterId() {
        return opponentCharacterIds.isEmpty() ? null : opponentCharacterIds.get(0);
    }
}
