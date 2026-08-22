package com.jjktbf.model.character;

import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashSet;

import static com.jjktbf.model.character.AbilityConditionParameter.*;

/** Authoritative metadata and validation for active ability conditions. */
public enum AbilityConditionType {
    ALL("All conditions (AND)", "Every child condition must be true."),
    ANY("Any condition (OR)", "At least one child condition must be true."),
    ALWAYS("Always active", "Generic effects activate once when battle processing begins; coded effects remain eligible at every natural runtime opportunity. It cannot be combined with another condition."),
    MANUAL_ACTIVATION("Manual activation", "Activates only when the battle controller requests this ability during planning."),
    BATTLE_STARTED("Battle started", "The battle has just started."),

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
    MOVE_WEAPON_REQUIRED("Move requires weapon", "The selected combatant uses a move carrying one of the weapon-type tags (Katana, Bow, Great Axe, Polearm, Staff).", ACTOR),
    MOVE_TYPE_TAGS_EXACTLY("Move damage tags match exactly", "The selected combatant uses a move with exactly these damage-type tags. Modifier tags are ignored.", ACTOR, MOVE_TAGS),
    ATTACK_HIT("Attack hit", "The selected combatant lands an attack.", ACTOR),
    ATTACK_MISSED("Attack missed", "The selected combatant misses an attack.", ACTOR),
    MOVE_BLOCKED("Attack blocked", "The selected combatant's attack is fully blocked.", ACTOR),
    EVENT_TARGET("Current event targets combatant", "The selected combatant is the target of the current battle event.", ACTOR),
    ATTACK_CONNECTED("Attack connected", "The selected combatant's current hit connected before block and defense.", ACTOR),
    CONNECTED_HIT_HAS_TAG("Connected hit has tag", "The selected combatant's current connected hit has this tag.", ACTOR, MOVE_TAG),
    FATAL_DAMAGE("Fatal damage incoming", "The selected combatant is about to take damage or an effect that would reduce HP to zero.", ACTOR),

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
    STATUS_REMOVED("Status removed", "This status expires or is removed from the selected combatant.", ACTOR, STATUS_TYPE),

    CODED_STATE_AT_OR_ABOVE("Coded state at or above", "The selected combatant's compiled resource or state is at least this value.", ACTOR, CODED_ABILITY, AMOUNT),
    CODED_STATE_AT_OR_BELOW("Coded state at or below", "The selected combatant's compiled resource or state is at most this value.", ACTOR, CODED_ABILITY, AMOUNT);

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
        condition.masteryProgression = null;
        condition.actor = uses(ACTOR) ? AbilityConditionActor.SELF.name() : null;
        condition.percentage = uses(PERCENTAGE) ? 0.5 : null;
        condition.amount = uses(AMOUNT) ? defaultAmount() : null;
        condition.moveId = null;
        condition.moveTag = uses(MOVE_TAG)
            ? (this == CONNECTED_HIT_HAS_TAG
                ? MoveTag.PHYSICAL.name() : MoveTag.ATTACK.name())
            : null;
        condition.moveTags = uses(MOVE_TAGS)
            ? new java.util.ArrayList<>(java.util.List.of(MoveTag.PHYSICAL.name())) : null;
        condition.stat = uses(STAT) ? StatKey.VITALITY.fieldName : null;
        condition.statusType = uses(STATUS_TYPE)
            ? StatusEffectType.STRENGTH_INCREASE.name() : null;
        condition.codedAbilityKey = uses(CODED_ABILITY)
            ? CodedAbilityRegistry.stateKeys().get(0).key() : null;
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
        if (!uses(MOVE_TAGS)) condition.moveTags = null;
        if (!uses(STAT)) condition.stat = null;
        if (!uses(STATUS_TYPE)) condition.statusType = null;
        if (!uses(CODED_ABILITY)) condition.codedAbilityKey = null;
        if (!uses(TICK)) condition.tick = null;
        if (!uses(ROUND)) condition.round = null;
        if (!uses(PHASE)) condition.phase = null;
        if (!isGroup()) condition.children = null;
        Set<String> allowedProgressions = masteryProgressionFields();
        if (condition.masteryProgression != null) {
            condition.masteryProgression = condition.masteryProgression.entrySet().stream()
                .filter(entry -> allowedProgressions.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(
                    java.util.Map.Entry::getKey,
                    java.util.Map.Entry::getValue,
                    (left, right) -> left,
                    java.util.LinkedHashMap::new));
            if (condition.masteryProgression.isEmpty()) condition.masteryProgression = null;
        }
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
        if (type.uses(MOVE_TAGS)) {
            if (condition.moveTags == null || condition.moveTags.isEmpty()) {
                return path + " needs at least one damage-type tag.";
            }
            Set<MoveTag> selected = EnumSet.noneOf(MoveTag.class);
            for (String storedTag : condition.moveTags) {
                MoveTag tag;
                try { tag = MoveTag.valueOf(storedTag); }
                catch (Exception ex) { return path + " needs valid damage-type tags."; }
                if (!MoveTag.TYPE_TAGS.contains(tag)) {
                    return path + " can only use damage-type tags.";
                }
                if (!selected.add(tag)) return path + " cannot repeat a damage-type tag.";
            }
        }
        if (type.uses(STAT)) {
            try { StatKey.fromString(condition.stat); }
            catch (Exception ex) { return path + " needs a valid character stat."; }
        }
        if (type.uses(STATUS_TYPE)) {
            try { StatusEffectType.fromName(condition.statusType); }
            catch (Exception ex) { return path + " needs a valid status."; }
        }
        if (type.uses(CODED_ABILITY)
            && !CodedAbilityRegistry.supportsStateKey(condition.codedAbilityKey)) {
            return path + " needs a valid coded state.";
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
        String progressionError = TechniqueMasteryProgressions.validationError(
            condition.masteryProgression, type.masteryProgressionFields());
        if (progressionError != null) return path + ": " + progressionError;
        if (condition.masteryProgression != null && !condition.masteryProgression.isEmpty()) {
            for (int mastery = 0; mastery <= CharacterStats.MAX_STAT; mastery++) {
                AbilityConditionData resolved;
                try {
                    resolved = TechniqueMasteryResolver.resolve(condition, mastery);
                } catch (RuntimeException exception) {
                    return path + " has invalid mastery progression at CTM " + mastery + ".";
                }
                resolved.masteryProgression = null;
                String error = validationError(resolved, path);
                if (error != null) return "At CTM " + mastery + ": " + error;
            }
        }
        return null;
    }

    /** Numeric fields available for CTM progression on this condition type. */
    public Set<String> masteryProgressionFields() {
        Set<String> fields = new LinkedHashSet<>();
        if (uses(PERCENTAGE)) fields.add(TechniqueMasteryProgressions.PERCENTAGE);
        if (uses(AMOUNT)) fields.add(TechniqueMasteryProgressions.AMOUNT);
        if (uses(TICK)) fields.add(TechniqueMasteryProgressions.TICK);
        if (uses(ROUND)) fields.add(TechniqueMasteryProgressions.ROUND);
        return Collections.unmodifiableSet(fields);
    }

    /** Validate a condition tree used to calculate the owner's current move cost. */
    static String moveCostConditionError(AbilityConditionData condition) {
        if (condition == null) return "CE-cost conditions need a move predicate.";
        AbilityConditionType type;
        try { type = fromName(condition.type); }
        catch (Exception ex) { return "CE-cost condition has an invalid type."; }
        if (type.isGroup()) {
            if (condition.children == null || condition.children.isEmpty()) {
                return "CE-cost condition groups need at least one child.";
            }
            for (AbilityConditionData child : condition.children) {
                String error = moveCostConditionError(child);
                if (error != null) return error;
            }
            return null;
        }
        if (type != MOVE_USED && type != MOVE_TAG_USED
            && type != MOVE_WEAPON_REQUIRED && type != MOVE_TYPE_TAGS_EXACTLY) {
            return "CE-cost conditions can only inspect the move being used.";
        }
        if (!AbilityConditionActor.SELF.name().equals(condition.actor)) {
            return "CE-cost conditions must inspect SELF's move.";
        }
        return null;
    }

    /** Evaluate a validated move-only condition tree for the owner's current move. */
    static boolean matchesMoveCostCondition(AbilityConditionData condition, Move move) {
        if (condition == null || move == null) return false;
        AbilityConditionType type;
        try { type = fromName(condition.type); }
        catch (Exception ex) { return false; }
        if (type == ALL) {
            return condition.children != null && !condition.children.isEmpty()
                && condition.children.stream().allMatch(child -> matchesMoveCostCondition(child, move));
        }
        if (type == ANY) {
            return condition.children != null && condition.children.stream()
                .anyMatch(child -> matchesMoveCostCondition(child, move));
        }
        if (!AbilityConditionActor.SELF.name().equals(condition.actor)) return false;
        return switch (type) {
            case MOVE_USED -> move.getId().equals(condition.moveId);
            case MOVE_TAG_USED -> move.hasTag(condition.moveTag);
            case MOVE_WEAPON_REQUIRED -> move.hasWeaponTag();
            case MOVE_TYPE_TAGS_EXACTLY -> moveHasExactTypeTags(move, condition.moveTags);
            default -> false;
        };
    }

    /** True when a move has precisely these damage-nature tags, ignoring modifiers. */
    public static boolean moveHasExactTypeTags(Move move, java.util.List<String> storedTags) {
        if (move == null || storedTags == null || storedTags.isEmpty()) return false;
        Set<MoveTag> expected = EnumSet.noneOf(MoveTag.class);
        for (String storedTag : storedTags) {
            try {
                MoveTag tag = MoveTag.valueOf(storedTag);
                if (!MoveTag.TYPE_TAGS.contains(tag) || !expected.add(tag)) return false;
            } catch (Exception ex) {
                return false;
            }
        }
        Set<MoveTag> actual = EnumSet.noneOf(MoveTag.class);
        for (MoveTag tag : move.getTags()) {
            if (MoveTag.TYPE_TAGS.contains(tag)) actual.add(tag);
        }
        return actual.equals(expected);
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
