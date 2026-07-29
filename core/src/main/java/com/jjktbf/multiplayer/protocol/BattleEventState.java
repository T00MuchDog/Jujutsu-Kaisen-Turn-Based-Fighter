package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.character.coded.CodedAbilityState;

/** Wire-safe event with stable identifiers instead of runtime combat references. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BattleEventState(
    String eventId,
    BattleEventType type,
    int roundNumber,
    int tick,
    PlayerSide sourceSide,
    String sourceCharacterId,
    String sourceCharacterName,
    PlayerSide targetSide,
    String targetCharacterId,
    String targetCharacterName,
    String moveId,
    String moveName,
    Integer componentIndex,
    Integer value,
    CodedAbilityState codedAbilityState,
    String message
) {
    /** Source-compatible constructor for events without a component index. */
    public BattleEventState(
        String eventId,
        BattleEventType type,
        int roundNumber,
        int tick,
        PlayerSide sourceSide,
        String sourceCharacterId,
        String sourceCharacterName,
        PlayerSide targetSide,
        String targetCharacterId,
        String targetCharacterName,
        String moveId,
        String moveName,
        Integer value,
        CodedAbilityState codedAbilityState,
        String message
    ) {
        this(eventId, type, roundNumber, tick, sourceSide, sourceCharacterId,
            sourceCharacterName, targetSide, targetCharacterId, targetCharacterName,
            moveId, moveName, null, value, codedAbilityState, message);
    }
}
