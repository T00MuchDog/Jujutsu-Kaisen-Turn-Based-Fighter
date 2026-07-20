package com.jjktbf.model.character.coded;

import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;

import java.util.List;

/** Battle-time behavior for an ability whose mechanics cannot be composed in the editor. */
public interface CodedAbilityRuntime {

    List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger);

    List<CombatEvent> onMoveUnleashed(BattleState state, Move move, int tick);

    boolean preventFatalDamage();

    List<CombatEvent> drainPendingEvents(int tick);

    CodedAbilityState state();
}
