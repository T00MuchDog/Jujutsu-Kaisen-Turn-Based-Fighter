package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.character.coded.CodedAbilityState;

/**
 * Wire-safe event with stable identifiers instead of runtime combat references.
 *
 * <p>In addition to the team side and character-definition id/name, events now
 * carry the source and target combatant <em>instance</em> ids so duplicate
 * summons of the same definition are distinguishable on the wire. Instance ids
 * are null for legacy 1v1 events.
 */
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
    String message,
    String sourceInstanceId,
    String targetInstanceId
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

    /** Full constructor without instance ids (legacy callers). */
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
        Integer componentIndex,
        Integer value,
        CodedAbilityState codedAbilityState,
        String message
    ) {
        this(eventId, type, roundNumber, tick, sourceSide, sourceCharacterId,
            sourceCharacterName, targetSide, targetCharacterId, targetCharacterName,
            moveId, moveName, componentIndex, value, codedAbilityState, message, null, null);
    }
}
