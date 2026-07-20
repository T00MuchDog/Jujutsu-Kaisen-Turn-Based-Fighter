package com.jjktbf.multiplayer.protocol;

/** Character resources captured before the current wire round is resolved. */
public record RoundStartCharacterState(
    PlayerSide side,
    int currentHp,
    int maxHp,
    int currentCe,
    int maxCe
) {
}
