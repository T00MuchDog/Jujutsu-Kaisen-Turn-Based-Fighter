package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.character.coded.CodedAbilityState;

import java.util.List;

/** Complete battle-time state of a participant's canonical character. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterState(
    String characterId,
    String name,
    int currentHp,
    int maxHp,
    int currentCe,
    int maxCe,
    int currentAp,
    int maxAp,
    int currentDefense,
    boolean inBlackFlashState,
    int consecutiveBfsHits,
    Integer bfsExpiresAfterRound,
    List<StatusEffectState> statusEffects,
    List<CodedAbilityState> codedAbilities,
    List<MoveState> knownMoves,
    PlanState plan
) {
    public CharacterState {
        statusEffects = statusEffects == null ? List.of() : List.copyOf(statusEffects);
        codedAbilities = codedAbilities == null ? List.of() : List.copyOf(codedAbilities);
        knownMoves = knownMoves == null ? List.of() : List.copyOf(knownMoves);
    }
}
