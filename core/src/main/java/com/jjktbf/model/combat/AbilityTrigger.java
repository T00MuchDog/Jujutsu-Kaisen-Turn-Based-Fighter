package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.StatusEffectType;

/** One semantic battle event against which passive activation predicates are evaluated. */
public record AbilityTrigger(
    Type type,
    BattleCombatant actor,
    BattleCombatant target,
    Move move,
    StatusEffectType status,
    int amount,
    int tick,
    BattleState.Phase phase
) {
    public enum Type {
        BATTLE_START,
        ROUND_START,
        PHASE_REACHED,
        TIMELINE_TICK,
        MOVE_USED,
        ATTACK_HIT,
        ATTACK_MISSED,
        MOVE_BLOCKED,
        BLACK_FLASH,
        HEALED,
        DAMAGE,
        CE_SPENT,
        CE_LOST,
        CE_RESTORED,
        STATUS_APPLIED,
        STATUS_REMOVED
    }

    public static AbilityTrigger simple(Type type) {
        return new AbilityTrigger(type, null, null, null, null, 0, 0, null);
    }

    public static AbilityTrigger phase(BattleState.Phase phase) {
        return new AbilityTrigger(Type.PHASE_REACHED, null, null, null, null, 0, 0, phase);
    }

    public static AbilityTrigger tick(int tick) {
        return new AbilityTrigger(Type.TIMELINE_TICK, null, null, null, null, 0, tick, null);
    }

    public static AbilityTrigger move(Type type, BattleCombatant actor, BattleCombatant target, Move move, int tick) {
        return new AbilityTrigger(type, actor, target, move, null, 0, tick, null);
    }

    public static AbilityTrigger amount(Type type, BattleCombatant actor, BattleCombatant target, int amount, int tick) {
        return new AbilityTrigger(type, actor, target, null, null, amount, tick, null);
    }

    public static AbilityTrigger status(Type type, BattleCombatant actor, StatusEffectType status, int tick) {
        return new AbilityTrigger(type, actor, null, null, status, 0, tick, null);
    }
}
