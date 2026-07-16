package com.jjktbf.multiplayer.engine;

import com.jjktbf.model.character.Character;
import com.jjktbf.multiplayer.protocol.PlayerSide;

import java.util.Objects;

/** Canonical participant input used to create an authoritative battle session. */
public record MatchParticipant(
    String playerId,
    String displayName,
    Character character,
    PlayerSide side
) {
    public MatchParticipant {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId cannot be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        Objects.requireNonNull(character, "character");
        Objects.requireNonNull(side, "side");
    }
}
