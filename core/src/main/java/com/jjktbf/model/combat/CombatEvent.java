package com.jjktbf.model.combat;

import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.move.Move;

/**
 * Immutable record of something that happened during combat resolution.
 *
 * CombatEvents are produced by CombatResolver and consumed by the View layer.
 * This is the Observer bridge — the core never calls the view directly;
 * instead it emits events that the view renders.
 *
 * The view layer can receive a List<CombatEvent> after each tick or round
 * and render them in sequence without any coupling to combat logic.
 */
public class CombatEvent {

    public enum Type {
        // Move execution
        MOVE_FIRED,         // move unleashed — attack resolved
        MOVE_MISSED,
        MOVE_BLOCKED,        // block fully negated the damage (BLOCK at 100% reduction)
        MOVE_BLOCK_REDUCED,  // block reduced but did not fully negate damage
        MOVE_DODGED,         // defender's DODGE defense avoided the attack entirely
        MOVE_PARRIED,        // defender's PARRY negated the attack (attacker may be staggered)
        MOVE_STUNNED,       // interrupt or stun effect removed this action segment

        // Targeting
        TARGET_RETARGETED,  // a single-target move's invalid target was retargeted at fire time

        // Summoning
        MOVE_SUMMON,        // a move effect row enqueued a shikigami summon (pre-materialization)

        // Damage
        DAMAGE_DEALT,
        DAMAGE_IGNORED,
        HP_RESTORED,
        MAX_HP_CHANGED,
        MAX_CE_CHANGED,
        BLACK_FLASH,        // BF proc — includes BF damage

        // CE
        CE_DRAINED,
        CE_RESTORED,
        CE_DEPLETED,        // combatant hit 0 CE

        // Status
        STATUS_APPLIED,
        STATUS_EXPIRED,

        // Combatant lifecycle
        COMBATANT_SUMMONED,
        COMBATANT_DEFEATED,
        COMBATANT_REMOVED,

        // BFS
        BFS_EXPIRED,

        // Abilities
        ABILITY_ACTIVATED,
        RATIO_TRIGGERED,

        // Round / battle
        ROUND_END,
        BATTLE_OVER
    }

    private final Type            type;
    private final BattleCombatant source;     // who caused the event (may be null for system events)
    private final BattleCombatant target;     // who was affected (may be null)
    private final Move            move;       // relevant move (may be null)
    private final int             intValue;   // damage, CE amount, etc.
    private final int             tick;       // AP tick this event occurred on (0 = system/round events)
    /** Zero-based hit component index, or null for move-level/system events. */
    private final Integer         componentIndex;
    /** Optional post-event resource snapshot for a coded ability. */
    private final CodedAbilityState codedAbilityState;
    private final String          message;    // human-readable description

    private CombatEvent(Builder b) {
        this.type      = b.type;
        this.source    = b.source;
        this.target    = b.target;
        this.move      = b.move;
        this.intValue  = b.intValue;
        this.tick      = b.tick;
        this.componentIndex = b.componentIndex;
        this.codedAbilityState = b.codedAbilityState;
        this.message   = b.message;
    }

    public Type            getType()     { return type; }
    public BattleCombatant getSource()   { return source; }
    public BattleCombatant getTarget()   { return target; }
    public Move            getMove()     { return move; }
    public int             getIntValue() { return intValue; }
    public int             getTick()     { return tick; }
    public Integer         getComponentIndex() { return componentIndex; }
    public CodedAbilityState getCodedAbilityState() { return codedAbilityState; }
    public String          getMessage()  { return message; }

    @Override
    public String toString() {
        return String.format("[%s] %s", type, message);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder of(Type type) { return new Builder(type); }

    public static class Builder {
        private final Type type;
        private BattleCombatant source;
        private BattleCombatant target;
        private Move            move;
        private int             intValue;
        private int             tick;
        private Integer         componentIndex;
        private CodedAbilityState codedAbilityState;
        private String          message = "";

        private Builder(Type type) { this.type = type; }

        public Builder source(BattleCombatant v)  { this.source   = v; return this; }
        public Builder target(BattleCombatant v)  { this.target   = v; return this; }
        public Builder move(Move v)               { this.move     = v; return this; }
        public Builder intValue(int v)            { this.intValue = v; return this; }
        public Builder tick(int v)                { this.tick     = v; return this; }
        public Builder componentIndex(Integer v)  { this.componentIndex = v; return this; }
        public Builder codedAbilityState(CodedAbilityState v) {
            this.codedAbilityState = v;
            return this;
        }
        public Builder message(String v)          { this.message  = v; return this; }

        public CombatEvent build() { return new CombatEvent(this); }
    }
}
