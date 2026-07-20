package com.jjktbf.model.character;

import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.jjktbf.model.character.AbilityConditionParameter.*;

/** Authoritative metadata and validation for passive activation predicates. */
public enum AbilityConditionType {
    ALL("All conditions (AND)", "Every child condition must be true."),
    ANY("Any condition (OR)", "At least one child condition must be true."),
    ALWAYS("Always active", "Activates at battle start. It cannot be combined with another condition."),

    HP_PERCENT_AT_OR_BELOW("HP at or below %", "The selected combatant's HP reaches or falls below this percentage.", ACTOR, PERCENTAGE),
    HP_PERCENT_AT_OR_ABOVE("HP at or above %", "The selected combatant's HP reaches or rises above this percentage.", ACTOR, PERCENTAGE),
    HP_VALUE_AT_OR_BELOW("HP at or below value", "The selected combatant's HP reaches or falls below this value.", ACTOR, AMOUNT),
    HP_VALUE_AT_OR_ABOVE("HP at or above value", "The selected combatant's HP reaches or rises above this value.", ACTOR, AMOUNT),
    CE_PERCENT_AT_OR_BELOW("CE at or below %", "The selected combatant's CE reaches or falls below this percentage.", ACTOR, PERCENTAGE),
    CE_PERCENT_AT_OR_ABOVE("CE at or above %", "The selected combatant's CE reaches or rises above this percentage.", ACTOR, PERCENTAGE),
    CE_VALUE_AT_OR_BELOW("CE at or below value", "The selected combatant's CE reaches or falls below this value.", ACTOR, AMOUNT),
    CE_VALUE_AT_OR_ABOVE("CE at or above value", "The selected combatant's CE reaches or rises above this value.", ACTOR, AMOUNT),

    BLACK_FLASH_HIT("Black Flash landed", "The selected combatant lands a Black Flash.", ACTOR),
    IN_BLACK_FLASH_STATE("In Black Flash State", "The selected combatant is currently in Black Flash State.", ACTOR),
    BLACK_FLASH_STREAK_AT_LEAST("Black Flash streak at least", "The selected combatant has this many consecutive Black Flashes in BFS.", ACTOR, AMOUNT),
    MOVE_USED("Specific move used", "The selected combatant uses the chosen move.", ACTOR, MOVE_ID),
    MOVE_TAG_USED("Move tag used", "The selected combatant uses a move with the chosen tag.", ACTOR, MOVE_TAG),
    ATTACK_HIT("Attack hit", "The selected combatant lands an attack.", ACTOR),
    ATTACK_MISSED("Attack missed", "The selected combatant misses an attack.", ACTOR),
    MOVE_BLOCKED("Attack blocked", "The selected combatant's attack is fully blocked.", ACTOR),

    TIMELINE_POINT_REACHED("Timeline point reached", "The action counter reaches this tick.", TICK),
    ROUND_REACHED("Round reached", "The battle reaches this round.", ROUND),
    TIMELINE_POINT_ON_ROUND("Timeline point on round", "The action counter reaches this tick during this round.", TICK, ROUND),
    EVERY_N_ROUNDS("Every N rounds", "Activates at the start of every Nth round.", ROUND),
    PHASE_REACHED("Battle phase reached", "The battle enters the selected planning or execution phase.", PHASE),

    HEALED("HP healed", "The selected combatant heals at least this much HP. Zero means any amount.", ACTOR, AMOUNT),
    DAMAGE_DEALT_AT_LEAST("Damage dealt", "The selected combatant deals at least this much damage in one instance.", ACTOR, AMOUNT),
    DAMAGE_TAKEN_AT_LEAST("Damage taken", "The selected combatant takes at least this much damage in one instance.", ACTOR, AMOUNT),
    CE_SPENT_AT_LEAST("CE spent", "The selected combatant voluntarily spends at least this much CE in one instance.", ACTOR, AMOUNT),
    CE_LOST_AT_LEAST("CE lost or drained", "The selected combatant loses at least this much CE in one instance.", ACTOR, AMOUNT),
    CE_RESTORED_AT_LEAST("CE restored", "The selected combatant restores at least this much CE in one instance.", ACTOR, AMOUNT),

    STAT_AT_OR_ABOVE("Character stat at or above", "The selected combatant's character stat is at or above this value.", ACTOR, STAT, AMOUNT),
    STAT_AT_OR_BELOW("Character stat at or below", "The selected combatant's character stat is at or below this value.", ACTOR, STAT, AMOUNT),
    HAS_STATUS("Has status", "The selected combatant is affected by this status.", ACTOR, STATUS_TYPE),
    DOES_NOT_HAVE_STATUS("Does not have status", "The selected combatant is not affected by this status.", ACTOR, STATUS_TYPE),
    STATUS_APPLIED("Status applied", "The selected combatant receives this status.", ACTOR, STATUS_TYPE),
    STATUS_REMOVED("Status removed", "This status expires or is removed from the selected combatant.", ACTOR, STATUS_TYPE);

    private final String displayName;
    private final String description;
    private final EnumSet<AbilityConditionParameter> parameters;

    AbilityConditionType(String displayName, String description, AbilityConditionParameter... parameters) {
        this.displayName = displayName;
        this.description = description;
        this.parameters = EnumSet.noneOf(AbilityConditionParameter.class);
        Collections.addAll(this.parameters, parameters);
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
    public boolean uses(AbilityConditionParameter parameter) { return parameters.contains(parameter); }
    public Set<AbilityConditionParameter> parameters() { return Collections.unmodifiableSet(parameters); }
    public boolean isGroup() { return this == ALL || this == ANY; }

    public static AbilityConditionType fromName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Condition type is required.");
        return valueOf(name.trim().toUpperCase());
    }

    public AbilityConditionData createDefault() {
        AbilityConditionData condition = new AbilityConditionData();
        reset(condition);
        return condition;
    }

    public void reset(AbilityConditionData condition) {
        condition.type = name();
        condition.actor = uses(ACTOR) ? AbilityConditionActor.SELF.name() : null;
        condition.percentage = uses(PERCENTAGE) ? 0.5 : null;
        condition.amount = uses(AMOUNT) ? defaultAmount() : null;
        condition.moveId = null;
        condition.moveTag = uses(MOVE_TAG) ? MoveTag.ATTACK.name() : null;
        condition.stat = uses(STAT) ? StatKey.VITALITY.fieldName : null;
        condition.statusType = uses(STATUS_TYPE) ? StatusEffectType.POISON.name() : null;
        condition.tick = uses(TICK) ? 1 : null;
        condition.round = uses(ROUND) ? 1 : null;
        condition.phase = uses(PHASE) ? BattleState.Phase.PLANNING.name() : null;
        condition.children = isGroup() ? new java.util.ArrayList<>() : null;
    }

    public void clearUnusedFields(AbilityConditionData condition) {
        condition.type = name();
        if (!uses(ACTOR)) condition.actor = null;
        if (!uses(PERCENTAGE)) condition.percentage = null;
        if (!uses(AMOUNT)) condition.amount = null;
        if (!uses(MOVE_ID)) condition.moveId = null;
        if (!uses(MOVE_TAG)) condition.moveTag = null;
        if (!uses(STAT)) condition.stat = null;
        if (!uses(STATUS_TYPE)) condition.statusType = null;
        if (!uses(TICK)) condition.tick = null;
        if (!uses(ROUND)) condition.round = null;
        if (!uses(PHASE)) condition.phase = null;
        if (!isGroup()) condition.children = null;
    }

    public static String validationError(AbilityConditionData root) {
        if (root == null) return null;
        if (root.containsAlways() && !ALWAYS.name().equalsIgnoreCase(root.type)) {
            return "Always active cannot be combined with another condition.";
        }
        return validationError(root, "Condition");
    }

    private static String validationError(AbilityConditionData condition, String path) {
        if (condition == null) return path + " is missing.";
        AbilityConditionType type;
        try {
            type = fromName(condition.type);
        } catch (Exception ex) {
            return path + " has an invalid type.";
        }
        if (type.isGroup()) {
            if (condition.children == null || condition.children.isEmpty()) {
                return path + " group needs at least one child condition.";
            }
            for (int i = 0; i < condition.children.size(); i++) {
                String error = validationError(condition.children.get(i), path + " " + (i + 1));
                if (error != null) return error;
            }
            return null;
        }
        if (type.uses(ACTOR)) {
            try { AbilityConditionActor.valueOf(condition.actor); }
            catch (Exception ex) { return path + " needs SELF, ENEMY, or ANY."; }
        }
        if (type.uses(PERCENTAGE)
            && (condition.percentage == null || !Double.isFinite(condition.percentage)
                || condition.percentage < 0 || condition.percentage > 1)) {
            return path + " percentage must be between 0% and 100%.";
        }
        if (type.uses(AMOUNT) && condition.amount == null) return path + " needs an amount.";
        if (type.uses(AMOUNT) && condition.amount < 0) return path + " amount cannot be negative.";
        if (type.uses(MOVE_ID) && isBlank(condition.moveId)) return path + " needs a move.";
        if (type.uses(MOVE_TAG)) {
            try { MoveTag.valueOf(condition.moveTag); }
            catch (Exception ex) { return path + " needs a valid move tag."; }
        }
        if (type.uses(STAT)) {
            try { StatKey.fromString(condition.stat); }
            catch (Exception ex) { return path + " needs a valid character stat."; }
        }
        if (type.uses(STATUS_TYPE)) {
            try { StatusEffectType.valueOf(condition.statusType); }
            catch (Exception ex) { return path + " needs a valid status."; }
        }
        if (type.uses(TICK) && (condition.tick == null || condition.tick < 1)) {
            return path + " timeline point must be at least 1.";
        }
        if (type.uses(ROUND) && (condition.round == null || condition.round < 1)) {
            return path + " round must be at least 1.";
        }
        if (type.uses(PHASE)) {
            try {
                BattleState.Phase phase = BattleState.Phase.valueOf(condition.phase);
                if (phase == BattleState.Phase.BATTLE_OVER) return path + " cannot use BATTLE_OVER.";
            } catch (Exception ex) {
                return path + " needs a valid battle phase.";
            }
        }
        return null;
    }

    private int defaultAmount() {
        return switch (this) {
            case HEALED -> 0;
            case BLACK_FLASH_STREAK_AT_LEAST -> 1;
            default -> 1;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
