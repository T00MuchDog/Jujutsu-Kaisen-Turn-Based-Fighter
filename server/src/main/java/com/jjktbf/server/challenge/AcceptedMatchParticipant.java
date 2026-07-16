package com.jjktbf.server.challenge;

import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.multiplayer.protocol.PlayerSide;

import java.util.Objects;

/** Canonical participant data used to construct a later authoritative match. */
public record AcceptedMatchParticipant(
    String playerId,
    String displayName,
    PlayerSide side,
    String characterId,
    SorcererCharacter character
) {
    public AcceptedMatchParticipant {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(character, "character");
    }
}
