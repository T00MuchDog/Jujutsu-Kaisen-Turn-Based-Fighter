package com.jjktbf.model.character.coded;

/** Immutable, player-visible state exposed by a compiled ability runtime. */
public record CodedAbilityState(
    String key,
    String displayName,
    int currentValue,
    int maximumValue
) {
}
