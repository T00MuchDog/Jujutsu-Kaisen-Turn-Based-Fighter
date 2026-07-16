package com.jjktbf.multiplayer.protocol;

/** Immutable wire representation of one active status effect. */
public record StatusEffectState(
    String type,
    String displayName,
    int remainingRounds,
    double magnitude
) {
}
