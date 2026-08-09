package com.jjktbf.multiplayer.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.character.coded.CodedAbilityState;

import java.util.List;

/** Complete battle-time state of one combatant instance. */
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
    PlanState plan,
    String instanceId,
    String characterType,
    String role,
    String lifecycle,
    String summonerId,
    int rosterOrder,
    Integer maxActiveSummons
) {
    public CharacterState {
        statusEffects = statusEffects == null ? List.of() : List.copyOf(statusEffects);
        codedAbilities = codedAbilities == null ? List.of() : List.copyOf(codedAbilities);
        knownMoves = knownMoves == null ? List.of() : List.copyOf(knownMoves);
    }

    /** Protocol-v8 constructor for singular-character callers. */
    public CharacterState(
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
        this(
            characterId,
            name,
            currentHp,
            maxHp,
            currentCe,
            maxCe,
            currentAp,
            maxAp,
            currentDefense,
            inBlackFlashState,
            consecutiveBfsHits,
            bfsExpiresAfterRound,
            statusEffects,
            codedAbilities,
            knownMoves,
            plan,
            characterId,
            "SORCERER",
            "FIGHTER",
            "ACTIVE",
            null,
            0,
            null
        );
    }

    /** Source-compatible constructor for protocol-v9 multi-combatant callers. */
    public CharacterState(
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
        PlanState plan,
        String instanceId,
        String characterType,
        String role,
        String lifecycle,
        String summonerId,
        int rosterOrder
    ) {
        this(characterId, name, currentHp, maxHp, currentCe, maxCe, currentAp, maxAp,
            currentDefense, inBlackFlashState, consecutiveBfsHits, bfsExpiresAfterRound,
            statusEffects, codedAbilities, knownMoves, plan, instanceId, characterType,
            role, lifecycle, summonerId, rosterOrder, null);
    }
}
