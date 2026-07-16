package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Identity, connection, submission, and character state for one participant. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerState(
    String playerId,
    String displayName,
    PlayerSide side,
    boolean connected,
    boolean planSubmitted,
    Long disconnectDeadline,
    CharacterState character
) {
}
