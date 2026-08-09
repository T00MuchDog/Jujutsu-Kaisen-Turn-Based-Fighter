package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Marker features for the foundational Ten Shadows and Totality passives. */
public final class TenShadowsAbility implements CodedAbilityRuntime {

    public static final String KEY = "TEN_SHADOWS";
    public static final String TECHNIQUE = "TECHNIQUE";
    public static final String TOTALITY = "TOTALITY";

    private final Set<String> features;

    public TenShadowsAbility(Set<String> features) {
        this.features = features == null ? Set.of() : Set.copyOf(features);
    }

    public static boolean supportsFeature(String feature) {
        return TECHNIQUE.equals(feature) || TOTALITY.equals(feature);
    }

    @Override
    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<String> featureActive
    ) {
        return List.of();
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Ten Shadows", features.size(), features.size());
    }
}
