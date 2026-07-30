package com.jjktbf.model.character.coded;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.StatusEffect;

import java.util.List;

/**
 * Battle-time behavior for an ability whose mechanics cannot be composed in the editor.
 *
 * <p>Hardcoded ability features are expressed as editable
 * {@link AbilityEffectData#isCoded() coded ability effects}. Hardcoded move behavior is
 * likewise expressed as a self-effect or on-hit effect carrying a
 * {@link StatusEffect#isCoded() coded action}, not as state baked onto the wrapper.
 * The effect row is the unit that can be added, edited, or removed; this runtime owns
 * the compiled behavior selected by that row.
 */
public interface CodedAbilityRuntime {

    List<CombatEvent> onTrigger(BattleState state, AbilityTrigger trigger);

    /**
     * React to a coded effect row firing during move resolution.
     *
     * <p>Called once per coded effect row on the move: a coded <em>self</em>-effect
     * fires on unleash (hit/miss/block agnostic); a coded <em>on-hit</em> effect
     * fires only on a successful hit. The runtime decides what to do by inspecting
     * {@code effect.getCodedAbilityKey()} / {@code effect.getCodedAction()} and its
     * own feature set. Default no-op so a runtime only overrides what it needs.
     *
     * @param state     current battle state
     * @param effect    the coded effect row that fired
     * @param attacker  the combatant who unleashed the move
     * @param defender  the move's target, including for coded self-effects
     * @param tick      the AP tick the effect fired on
     */
    default List<CombatEvent> onEffectFired(
        BattleState state,
        StatusEffect effect,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick
    ) {
        return List.of();
    }

    /** Supply modifiers after an attacking move connects but before block and defense. */
    default CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick,
        RandomSource rng
    ) {
        return CodedHitModifiers.none();
    }

    /** Component-aware hook used by multi-hit moves. */
    default CodedHitModifiers onAttackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick,
        RandomSource rng
    ) {
        return onAttackConnected(attacker, defender, move, tick, rng);
    }

    /** React immediately before an incoming planned move is marked as fired. */
    default CodedMoveResponse beforeIncomingMove(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick
    ) {
        return CodedMoveResponse.none();
    }

    /** Advance effects measured by the universal AP-tick clock. */
    default List<CombatEvent> tickTimelineEffects(int tick) {
        return List.of();
    }

    /** Longest remaining universal-tick timer owned by this runtime. */
    default int getRemainingTimelineEffectTicks() {
        return 0;
    }

    boolean preventFatalDamage();

    List<CombatEvent> drainPendingEvents(int tick);

    CodedAbilityState state();
}
