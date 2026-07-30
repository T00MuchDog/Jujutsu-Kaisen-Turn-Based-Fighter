package com.jjktbf.model.character.coded;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityEffectData;

/** Source ability/effect row behind one compiled feature. */
public record CodedAbilityBinding(
    Ability ability,
    AbilityEffectData effect,
    int abilityIndex,
    int effectIndex
) {
}
