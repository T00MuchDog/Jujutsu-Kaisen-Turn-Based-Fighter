package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.StatusEffectType;

/** One semantic battle event against which active ability conditions are evaluated. */
public record AbilityTrigger(
    Type type,
    BattleCombatant actor,
    BattleCombatant target,
    Move move,
    StatusEffectType status,
    int amount,
    int tick,
    BattleState.Phase phase,
    HitComponent hitComponent,
    String abilityId
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
        STATUS_REMOVED,
        MANUAL_ACTIVATION,
        ATTACK_CONNECTED,
        INCOMING_MOVE,
        FATAL_DAMAGE
    }

    public static AbilityTrigger simple(Type type) {
        return new AbilityTrigger(type, null, null, null, null, 0, 0, null, null, null);
    }

    /** Battle-start trigger scoped to the combatant that just joined this battle. */
    public static AbilityTrigger battleStart(BattleCombatant combatant) {
        return new AbilityTrigger(
            Type.BATTLE_START, combatant, null, null, null, 0, 0, null, null, null);
    }

    /** Round-start trigger scoped to the combatant entering this round. */
    public static AbilityTrigger roundStart(BattleCombatant combatant) {
        return new AbilityTrigger(
            Type.ROUND_START, combatant, null, null, null, 0, 0, null, null, null);
    }

    public static AbilityTrigger phase(BattleState.Phase phase) {
        return new AbilityTrigger(Type.PHASE_REACHED, null, null, null, null, 0, 0, phase, null, null);
    }

    public static AbilityTrigger tick(int tick) {
        return new AbilityTrigger(Type.TIMELINE_TICK, null, null, null, null, 0, tick, null, null, null);
    }

    public static AbilityTrigger move(Type type, BattleCombatant actor, BattleCombatant target, Move move, int tick) {
        return new AbilityTrigger(type, actor, target, move, null, 0, tick, null, null, null);
    }

    public static AbilityTrigger amount(Type type, BattleCombatant actor, BattleCombatant target, int amount, int tick) {
        return new AbilityTrigger(type, actor, target, null, null, amount, tick, null, null, null);
    }

    public static AbilityTrigger status(Type type, BattleCombatant actor, StatusEffectType status, int tick) {
        return new AbilityTrigger(type, actor, null, null, status, 0, tick, null, null, null);
    }

    public static AbilityTrigger manual(BattleCombatant actor, String abilityId, int tick) {
        return new AbilityTrigger(
            Type.MANUAL_ACTIVATION, actor, null, null, null, 0, tick, null, null, abilityId);
    }

    public static AbilityTrigger attackConnected(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick
    ) {
        return new AbilityTrigger(
            Type.ATTACK_CONNECTED, attacker, defender, move, null, 0, tick, null,
            component, null);
    }

    public static AbilityTrigger attackHit(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int tick
    ) {
        return new AbilityTrigger(
            Type.ATTACK_HIT, attacker, defender, move, null, 0, tick, null,
            component, null);
    }

    public static AbilityTrigger incomingMove(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        int tick
    ) {
        return new AbilityTrigger(
            Type.INCOMING_MOVE, attacker, defender, move, null, 0, tick, null, null, null);
    }

    public static AbilityTrigger fatalDamage(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move move,
        HitComponent component,
        int amount,
        int tick
    ) {
        return new AbilityTrigger(
            Type.FATAL_DAMAGE, attacker, defender, move, null, amount, tick, null,
            component, null);
    }
}
