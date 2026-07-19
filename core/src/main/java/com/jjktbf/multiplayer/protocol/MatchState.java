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
    List<RoundStartCharacterState> roundStartCharacterStates,
    PlayerSide winnerSide,
    String winnerPlayerId,
    String endReason,
    long stateVersion,
    List<BattleEventState> recentEvents,
    long serverTimestamp
) {
    public MatchState {
        players = players == null ? List.of() : List.copyOf(players);
        roundStartCharacterStates = roundStartCharacterStates == null
            ? List.of()
            : List.copyOf(roundStartCharacterStates);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }

    public MatchState(
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
        this(
            matchId,
            status,
            gameVersion,
            protocolVersion,
            ruleset,
            phase,
            roundNumber,
            currentTick,
            players,
            List.of(),
            winnerSide,
            winnerPlayerId,
            endReason,
            stateVersion,
            recentEvents,
            serverTimestamp
        );
    }

    /** Looks up a participant without relying on list order. */
    public Optional<PlayerState> player(PlayerSide side) {
        return players.stream().filter(player -> player.side() == side).findFirst();
    }
}
