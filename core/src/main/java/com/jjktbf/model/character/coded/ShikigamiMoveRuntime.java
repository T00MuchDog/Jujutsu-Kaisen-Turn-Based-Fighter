package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.StatusEffect;

import java.util.List;
import java.util.function.Predicate;

/** Stateless coded actions shared by summoned shikigami moves. */
public final class ShikigamiMoveRuntime implements CodedAbilityRuntime {

    public static final String KEY = "SHIKIGAMI";
    public static final String DESUMMON_SELF = "DESUMMON_SELF";

    @Override
    public List<CombatEvent> onTrigger(
        BattleState state,
        AbilityTrigger trigger,
        Predicate<String> featureActive
    ) {
        return List.of();
    }

    @Override
    public List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        if (state != null && attacker != null && attacker.isSummon()
            && KEY.equalsIgnoreCase(effect.getCodedAbilityKey())
            && DESUMMON_SELF.equalsIgnoreCase(effect.getCodedAction())) {
            state.voluntarilyDesummon(attacker);
        }
        return List.of();
    }

    @Override
    public List<CombatEvent> drainPendingEvents(int tick) {
        return List.of();
    }

    @Override
    public CodedAbilityState state() {
        return new CodedAbilityState(KEY, "Shikigami", 0, 0);
    }
}
