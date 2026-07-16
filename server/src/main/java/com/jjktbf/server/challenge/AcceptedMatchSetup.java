package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.MatchStatus;

import java.util.List;
import java.util.Objects;

/** Atomic challenge-acceptance output consumed by the active MatchManager. */
public record AcceptedMatchSetup(
    String matchId,
    String challengeId,
    MatchStatus status,
    long serverSeed,
    String gameVersion,
    int protocolVersion,
    String ruleset,
    long createdAt,
    AcceptedMatchParticipant playerOne,
    AcceptedMatchParticipant playerTwo
) {
    public AcceptedMatchSetup {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(gameVersion, "gameVersion");
        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(playerOne, "playerOne");
        Objects.requireNonNull(playerTwo, "playerTwo");
    }

    public List<AcceptedMatchParticipant> participants() {
        return List.of(playerOne, playerTwo);
    }
}
