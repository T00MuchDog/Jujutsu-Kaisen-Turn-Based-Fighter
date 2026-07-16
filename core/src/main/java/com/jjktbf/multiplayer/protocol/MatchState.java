package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Optional;

/** Complete authoritative and versioned match snapshot. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchState(
    String matchId,
    MatchStatus status,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    BattlePhase phase,
    int roundNumber,
    int currentTick,
    List<PlayerState> players,
    PlayerSide winnerSide,
    String winnerPlayerId,
    String endReason,
    long stateVersion,
    List<BattleEventState> recentEvents,
    long serverTimestamp
) {
    public MatchState {
        players = players == null ? List.of() : List.copyOf(players);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }

    /** Looks up a participant without relying on list order. */
    public Optional<PlayerState> player(PlayerSide side) {
        return players.stream().filter(player -> player.side() == side).findFirst();
    }
}
