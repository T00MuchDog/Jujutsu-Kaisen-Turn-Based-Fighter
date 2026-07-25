package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.StatusEffect;

import java.util.ArrayList;
import java.util.List;

/** Generic dispatcher and state holder for compiled ability runtimes on one combatant. */
public final class CodedAbilities {

    private final List<CodedAbilityRuntime> runtimes;

    CodedAbilities(List<CodedAbilityRuntime> runtimes) {
        this.runtimes = runtimes == null ? List.of() : List.copyOf(runtimes);
    }

    public List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger) {
        List<CombatEvent> events = new ArrayList<>();
        for (CodedAbilityRuntime runtime : runtimes) {
            events.addAll(runtime.onTrigger(state, trigger));
        }
        return events;
    }

    /**
     * Dispatch a coded effect row to every runtime (see
     * {@link CodedAbilityRuntime#onEffectFired}). A runtime that does not own the
     * row's coded key/action returns an empty list.
     */
    public List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        List<CombatEvent> events = new ArrayList<>();
        for (CodedAbilityRuntime runtime : runtimes) {
            events.addAll(runtime.onEffectFired(state, effect, attacker, defender, tick));
        }
        return events;
    }

    public boolean preventFatalDamage() {
        for (CodedAbilityRuntime runtime : runtimes) {
            if (runtime.preventFatalDamage()) return true;
        }
        return false;
    }

    public List<CombatEvent> drainPendingEvents(int tick) {
        List<CombatEvent> events = new ArrayList<>();
        for (CodedAbilityRuntime runtime : runtimes) {
            events.addAll(runtime.drainPendingEvents(tick));
        }
        return events;
    }

    public List<CodedAbilityState> states() {
        return runtimes.stream().map(CodedAbilityRuntime::state).toList();
    }

}
