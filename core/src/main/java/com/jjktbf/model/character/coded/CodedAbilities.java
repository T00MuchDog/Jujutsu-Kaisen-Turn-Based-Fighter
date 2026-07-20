package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;

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

    public List<CombatEvent> onMoveUnleashed(BattleState state, Move move, int tick) {
        List<CombatEvent> events = new ArrayList<>();
        for (CodedAbilityRuntime runtime : runtimes) {
            events.addAll(runtime.onMoveUnleashed(state, move, tick));
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
