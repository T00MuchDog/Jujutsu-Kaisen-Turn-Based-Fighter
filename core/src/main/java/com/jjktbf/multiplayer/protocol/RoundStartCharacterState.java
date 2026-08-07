package com.jjktbf.multiplayer.protocol;

import com.jjktbf.model.character.coded.CodedAbilityState;

import java.util.List;

/** Character resources captured before the current wire round is resolved. */
public record RoundStartCharacterState(
    PlayerSide side,
    int currentHp,
    int maxHp,
    int currentCe,
    int maxCe,
    List<CodedAbilityState> codedAbilities,
    String instanceId
) {
    public RoundStartCharacterState {
        codedAbilities = codedAbilities == null ? List.of() : List.copyOf(codedAbilities);
    }

    /** Protocol-v8 constructor without a combatant instance id. */
    public RoundStartCharacterState(
        PlayerSide side,
        int currentHp,
        int maxHp,
        int currentCe,
        int maxCe,
        List<CodedAbilityState> codedAbilities
    ) {
        this(side, currentHp, maxHp, currentCe, maxCe, codedAbilities, null);
    }
}
