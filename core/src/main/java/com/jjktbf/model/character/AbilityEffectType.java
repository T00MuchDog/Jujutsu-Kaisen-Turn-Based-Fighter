package com.jjktbf.model.character;

import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.jjktbf.model.character.AbilityEffectParameter.DECIMAL;
import static com.jjktbf.model.character.AbilityEffectParameter.DURATION;
import static com.jjktbf.model.character.AbilityEffectParameter.INTEGER;
import static com.jjktbf.model.character.AbilityEffectParameter.MAGNITUDE;
import static com.jjktbf.model.character.AbilityEffectParameter.MOVE_ID;
import static com.jjktbf.model.character.AbilityEffectParameter.MOVE_SCOPE;
import static com.jjktbf.model.character.AbilityEffectParameter.STAT;
import static com.jjktbf.model.character.AbilityEffectParameter.STATUS_TYPE;
import static com.jjktbf.model.character.AbilityEffectParameter.TARGET;
import static com.jjktbf.model.character.AbilityEffectParameter.TECHNIQUE;
import static com.jjktbf.model.character.AbilityEffectParameter.TIMING;
import static com.jjktbf.model.character.AbilityEffectParameter.USES;
import static com.jjktbf.model.character.AbilityEffectParameter.BATTLE_STAT;

/**
 * Mechanical effects that can be composed into a passive ability.
 *
 * <p>Each type owns its editor-facing name, explanation, required parameters,
 * defaults, data cleanup, and validation. The graphics editor consumes this
 * metadata instead of presenting every field for every effect.</p>
 */
public enum AbilityEffectType {

    STAT_ADD(
        "Add to stat",
        "Permanently adds or subtracts a flat amount from one stat.",
        STAT, INTEGER),
    STAT_MULTIPLY(
        "Multiply stat",
        "Multiplies one stat after flat changes. 1.20 means 20% higher.",
        STAT, DECIMAL),
    STAT_DIVIDE(
        "Divide stat",
        "Divides one stat after flat changes. 2.00 halves it.",
        STAT, DECIMAL),
    STAT_SET_VALUE(
        "Set stat value",
        "Sets one stat to an exact value before flat additions.",
        STAT, INTEGER),
    STAT_SET_MIN(
        "Remove stat",
        "Sets one stat to 0, displayed as N/A.",
        STAT),
    STAT_BONUS_POINTS(
        "Change point budget",
        "Changes the character editor's point-buy budget.",
        INTEGER),

    CE_COST_TO_MINIMUM(
        "Minimum CE costs",
        "Forces matching moves to use their configured minimum CE cost.",
        MOVE_SCOPE),
    CE_COST_MULTIPLY(
        "Multiply CE costs",
        "Multiplies the CE cost of matching moves. 0.50 halves the cost.",
        MOVE_SCOPE, DECIMAL),

    MOVE_ACCURACY_ADD(
        "Change own accuracy",
        "Adds or subtracts accuracy points when using matching moves.",
        MOVE_SCOPE, INTEGER),
    MOVE_ACCURACY_MULTIPLY(
        "Multiply own accuracy",
        "Multiplies accuracy when using matching moves.",
        MOVE_SCOPE, DECIMAL),
    OPPONENT_ACCURACY_ADD(
        "Change enemy accuracy",
        "Adds or subtracts an enemy's accuracy when they attack this character.",
        MOVE_SCOPE, INTEGER),
    OPPONENT_ACCURACY_MULTIPLY(
        "Multiply enemy accuracy",
        "Multiplies an enemy's accuracy when they attack this character.",
        MOVE_SCOPE, DECIMAL),

    DAMAGE_MULTIPLY(
        "Multiply damage",
        "Multiplies damage dealt by matching moves.",
        MOVE_SCOPE, DECIMAL),
    GRANT_MOVE(
        "Grant move",
        "Adds one move to the character outside the normal slot and prerequisite rules.",
        MOVE_ID),
    BF_CHANCE_ADD(
        "Change Black Flash chance",
        "Adds or subtracts Black Flash chance. Enter 5 for five percentage points.",
        DECIMAL),
    UNLOCK_TECHNIQUE(
        "Unlock technique",
        "Lets the character learn and use moves belonging to another technique.",
        TECHNIQUE),
    MODIFY_DEFENSE(
        "Multiply defense",
        "Multiplies the character's effective defense.",
        DECIMAL),
    MODIFY_AP_BAR(
        "Change AP bar",
        "Adds or subtracts a flat amount from the character's AP bar.",
        INTEGER),
    AUTO_STATUS_APPLY(
        "Apply status automatically",
        "Applies a supported status at fight start, round start, or after a hit.",
        STATUS_TYPE, TARGET, TIMING, DURATION, MAGNITUDE),
    LOCK_MOVE_TAG(
        "Lock own move tag",
        "Prevents this character from selecting moves with one tag.",
        MOVE_SCOPE),
    COST_CE_PER_ROUND(
        "Round-start CE cost",
        "Drains CE before planning each round. Other passive effects remain active at 0 CE.",
        INTEGER),

    HEAL_HP(
        "Heal HP",
        "Immediately restores a flat amount of HP when the ability activates.",
        TARGET, INTEGER),
    HEAL_HP_PERCENT(
        "Heal max HP %",
        "Immediately restores a percentage of maximum HP.",
        TARGET, DECIMAL),
    RESTORE_CE(
        "Restore CE",
        "Immediately restores a flat amount of Cursed Energy.",
        TARGET, INTEGER),
    RESTORE_CE_PERCENT(
        "Restore max CE %",
        "Immediately restores a percentage of maximum Cursed Energy.",
        TARGET, DECIMAL),
    DRAIN_CE(
        "Drain CE",
        "Immediately removes a flat amount of Cursed Energy.",
        TARGET, INTEGER),
    DRAIN_CE_PERCENT(
        "Drain max CE %",
        "Immediately removes a percentage of maximum Cursed Energy.",
        TARGET, DECIMAL),
    DEAL_DIRECT_DAMAGE(
        "Deal direct damage",
        "Immediately deals fixed damage, bypassing accuracy and defense.",
        TARGET, INTEGER),
    DEAL_MAX_HP_DAMAGE(
        "Deal max HP % damage",
        "Immediately deals a percentage of the target's maximum HP as direct damage.",
        TARGET, DECIMAL),
    INSTANT_KILL(
        "Instant kill",
        "Immediately reduces the target to 0 HP unless fatal-hit protection is active.",
        TARGET),

    APPLY_STATUS(
        "Apply status",
        "Applies any status when the ability activates.",
        STATUS_TYPE, TARGET, DURATION, MAGNITUDE),
    REMOVE_STATUS(
        "Remove status",
        "Removes every instance of one status from the target.",
        STATUS_TYPE, TARGET),
    CLEAR_STATUSES(
        "Clear all statuses",
        "Removes every active status from the target.",
        TARGET),

    TEMP_STAT_ADD(
        "Timed character stat change",
        "Adds or subtracts from a character stat for a number of rounds.",
        STAT, TARGET, INTEGER, DURATION),
    TEMP_STAT_MULTIPLY(
        "Timed character stat multiplier",
        "Multiplies a character stat for a number of rounds.",
        STAT, TARGET, DECIMAL, DURATION),
    TEMP_STAT_SET_VALUE(
        "Timed character stat set",
        "Sets a character stat to an exact value for a number of rounds.",
        STAT, TARGET, INTEGER, DURATION),
    BATTLE_STAT_ADD(
        "Timed battle stat change",
        "Adds to a derived battle value for a number of rounds.",
        BATTLE_STAT, TARGET, DECIMAL, DURATION),
    BATTLE_STAT_MULTIPLY(
        "Timed battle stat multiplier",
        "Multiplies a derived battle value for a number of rounds.",
        BATTLE_STAT, TARGET, DECIMAL, DURATION),

    IGNORE_DAMAGE(
        "Ignore incoming damage",
        "Negates damaging instances. Uses = 1 for one hit or -1 for every hit during the duration.",
        TARGET, USES, DURATION),
    DAMAGE_SHIELD(
        "Damage shield",
        "Absorbs up to a fixed total amount of incoming damage during the duration.",
        TARGET, INTEGER, DURATION),
    SURVIVE_FATAL_DAMAGE(
        "Survive fatal damage",
        "Leaves the target at 1 HP when damage would defeat them.",
        TARGET, USES, DURATION),
    GUARANTEE_NEXT_HIT(
        "Guarantee next hit",
        "Makes the target's next eligible attack hit.",
        TARGET, USES, DURATION),
    GUARANTEE_NEXT_DODGE(
        "Guarantee next dodge",
        "Makes the target dodge the next attack against them.",
        TARGET, USES, DURATION),
    GUARANTEE_NEXT_BLACK_FLASH(
        "Guarantee next Black Flash",
        "Makes the target's next Black-Flash-eligible hit become a Black Flash.",
        TARGET, USES, DURATION),
    CANCEL_NEXT_MOVE(
        "Cancel next move",
        "Stuns the target's next move when it begins execution.",
        TARGET, USES, DURATION),
    TEMP_LOCK_MOVE_TAG(
        "Temporarily lock move tag",
        "Prevents the target from planning moves with one tag for the duration.",
        TARGET, MOVE_SCOPE, DURATION);

    private static final Set<StatusEffectType> SUPPORTED_AUTO_STATUSES =
        Collections.unmodifiableSet(EnumSet.allOf(StatusEffectType.class));

    private final String displayName;
    private final String description;
    private final EnumSet<AbilityEffectParameter> parameters;

    AbilityEffectType(String displayName, String description, AbilityEffectParameter... parameters) {
        this.displayName = displayName;
        this.description = description;
        this.parameters = EnumSet.noneOf(AbilityEffectParameter.class);
        Collections.addAll(this.parameters, parameters);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public boolean uses(AbilityEffectParameter parameter) {
        return parameters.contains(parameter);
    }

    public Set<AbilityEffectParameter> parameters() {
        return Collections.unmodifiableSet(parameters);
    }

    public static Set<StatusEffectType> supportedAutoStatuses() {
        return SUPPORTED_AUTO_STATUSES;
    }

    public static AbilityEffectType fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Effect type is required.");
        }
        return valueOf(name.trim().toUpperCase());
    }

    /** Create a new effect with useful, non-neutral defaults where possible. */
    public AbilityEffectData createDefault() {
        AbilityEffectData effect = new AbilityEffectData();
        reset(effect);
        return effect;
    }

    /** Replace all effect parameters with this type's defaults. */
    public void reset(AbilityEffectData effect) {
        effect.type = name();
        effect.stat = null;
        effect.intValue = null;
        effect.doubleValue = null;
        effect.moveTag = null;
        effect.moveId = null;
        effect.stringValue = null;
        effect.target = null;
        effect.timing = null;
        effect.durationRounds = null;
        effect.magnitude = null;
        effect.uses = null;

        if (uses(STAT)) effect.stat = StatKey.VITALITY.fieldName;
        if (uses(TARGET)) effect.target = AbilityEffectTarget.SELF.name();

        switch (this) {
            case STAT_ADD -> effect.intValue = 10;
            case STAT_MULTIPLY -> effect.doubleValue = 1.10;
            case STAT_DIVIDE -> effect.doubleValue = 2.0;
            case STAT_SET_VALUE -> effect.intValue = CharacterStats.BASELINE;
            case STAT_BONUS_POINTS -> effect.intValue = 10;
            case CE_COST_MULTIPLY, MOVE_ACCURACY_MULTIPLY,
                 OPPONENT_ACCURACY_MULTIPLY, DAMAGE_MULTIPLY,
                 MODIFY_DEFENSE -> effect.doubleValue = 1.10;
            case MOVE_ACCURACY_ADD, OPPONENT_ACCURACY_ADD -> effect.intValue = 10;
            case BF_CHANCE_ADD -> effect.doubleValue = 0.05;
            case MODIFY_AP_BAR -> effect.intValue = 10;
            case AUTO_STATUS_APPLY -> {
                effect.stringValue = StatusEffectType.FOCUS.name();
                effect.target = AbilityEffectTarget.SELF.name();
                effect.timing = AbilityEffectTiming.FIGHT_START.name();
                effect.durationRounds = -1;
                effect.magnitude = 0.10;
            }
            case LOCK_MOVE_TAG -> effect.moveTag = MoveTag.PHYSICAL.name();
            case COST_CE_PER_ROUND -> effect.intValue = 5;
            case HEAL_HP, RESTORE_CE, DRAIN_CE, DEAL_DIRECT_DAMAGE -> effect.intValue = 10;
            case HEAL_HP_PERCENT, RESTORE_CE_PERCENT, DRAIN_CE_PERCENT,
                 DEAL_MAX_HP_DAMAGE -> effect.doubleValue = 0.10;
            case APPLY_STATUS -> {
                effect.stringValue = StatusEffectType.POISON.name();
                effect.target = AbilityEffectTarget.ENEMY.name();
                effect.durationRounds = 1;
                effect.magnitude = 1.0;
            }
            case REMOVE_STATUS -> {
                effect.stringValue = StatusEffectType.POISON.name();
                effect.target = AbilityEffectTarget.SELF.name();
            }
            case CLEAR_STATUSES, INSTANT_KILL -> effect.target = AbilityEffectTarget.ENEMY.name();
            case TEMP_STAT_ADD -> {
                effect.intValue = 10;
                timedDefaults(effect);
            }
            case TEMP_STAT_MULTIPLY -> {
                effect.doubleValue = 1.10;
                timedDefaults(effect);
            }
            case TEMP_STAT_SET_VALUE -> {
                effect.intValue = CharacterStats.BASELINE;
                timedDefaults(effect);
            }
            case BATTLE_STAT_ADD -> {
                effect.stringValue = BattleStatKey.MAX_AP.name();
                effect.doubleValue = 10.0;
                timedDefaults(effect);
            }
            case BATTLE_STAT_MULTIPLY -> {
                effect.stringValue = BattleStatKey.DAMAGE_DEALT.name();
                effect.doubleValue = 1.10;
                timedDefaults(effect);
            }
            case IGNORE_DAMAGE, SURVIVE_FATAL_DAMAGE, GUARANTEE_NEXT_HIT,
                 GUARANTEE_NEXT_DODGE, GUARANTEE_NEXT_BLACK_FLASH, CANCEL_NEXT_MOVE -> {
                effect.target = AbilityEffectTarget.SELF.name();
                effect.uses = 1;
                effect.durationRounds = -1;
            }
            case DAMAGE_SHIELD -> {
                effect.target = AbilityEffectTarget.SELF.name();
                effect.intValue = 10;
                effect.durationRounds = -1;
            }
            case TEMP_LOCK_MOVE_TAG -> {
                effect.target = AbilityEffectTarget.ENEMY.name();
                effect.moveTag = MoveTag.CURSED_ENERGY.name();
                effect.durationRounds = 1;
            }
            default -> { }
        }
    }

    /** Fill missing relevant values and discard fields belonging to another type. */
    public void prepare(AbilityEffectData effect) {
        AbilityEffectData defaults = createDefault();
        effect.type = name();
        clearUnusedFields(effect);
        if (uses(STAT) && isBlank(effect.stat)) effect.stat = defaults.stat;
        if (uses(INTEGER) && effect.intValue == null) effect.intValue = defaults.intValue;
        if (uses(DECIMAL) && effect.doubleValue == null) effect.doubleValue = defaults.doubleValue;
        if (uses(MOVE_SCOPE) && (this == LOCK_MOVE_TAG || this == TEMP_LOCK_MOVE_TAG)
            && isBlank(effect.moveTag)) {
            effect.moveTag = defaults.moveTag;
        }
        if ((uses(TECHNIQUE) || uses(STATUS_TYPE)) && isBlank(effect.stringValue)) {
            effect.stringValue = defaults.stringValue;
        }
        if (uses(TARGET) && isBlank(effect.target)) effect.target = defaults.target;
        if (uses(TIMING) && isBlank(effect.timing)) effect.timing = defaults.timing;
        if (uses(DURATION) && effect.durationRounds == null) effect.durationRounds = defaults.durationRounds;
        if (uses(MAGNITUDE) && effect.magnitude == null) effect.magnitude = defaults.magnitude;
        if (uses(USES) && effect.uses == null) effect.uses = defaults.uses;
        if (uses(BATTLE_STAT) && isBlank(effect.stringValue)) effect.stringValue = defaults.stringValue;
    }

    /** Remove stale values so persisted JSON contains only parameters this type reads. */
    public void clearUnusedFields(AbilityEffectData effect) {
        if (!uses(STAT)) effect.stat = null;
        if (!uses(INTEGER)) effect.intValue = null;
        if (!uses(DECIMAL)) effect.doubleValue = null;
        if (!uses(MOVE_SCOPE)) effect.moveTag = null;
        if (!uses(MOVE_ID)) effect.moveId = null;
        if (!uses(TECHNIQUE) && !uses(STATUS_TYPE) && !uses(BATTLE_STAT)) effect.stringValue = null;
        if (!uses(TARGET)) effect.target = null;
        if (!uses(TIMING)) effect.timing = null;
        if (!uses(DURATION)) effect.durationRounds = null;
        if (!uses(MAGNITUDE)) effect.magnitude = null;
        if (!uses(USES)) effect.uses = null;
        if (!uses(BATTLE_STAT) && !uses(TECHNIQUE) && !uses(STATUS_TYPE)) effect.stringValue = null;
    }

    /** Return a user-facing validation error, or {@code null} when valid. */
    public String validationError(AbilityEffectData effect) {
        if (effect == null) return "Effect is missing.";

        if (uses(STAT)) {
            try {
                StatKey.fromString(effect.stat);
            } catch (Exception ex) {
                return "Choose a valid stat.";
            }
        }
        if (uses(INTEGER) && effect.intValue == null) return "Enter an integer value.";
        if (uses(DECIMAL) && !isFinite(effect.doubleValue)) return "Enter a valid decimal value.";
        if (uses(MOVE_SCOPE) && !isBlank(effect.moveTag)) {
            try {
                MoveTag.valueOf(effect.moveTag);
            } catch (Exception ex) {
                return "Choose a valid move tag.";
            }
        }
        if ((this == LOCK_MOVE_TAG || this == TEMP_LOCK_MOVE_TAG) && isBlank(effect.moveTag)) {
            return "Choose a move tag to lock.";
        }
        if (uses(MOVE_ID) && isBlank(effect.moveId)) return "Choose a move to grant.";
        if (uses(TECHNIQUE) && isBlank(effect.stringValue)) return "Choose a technique.";

        if (uses(STATUS_TYPE)) {
            StatusEffectType status;
            try {
                status = StatusEffectType.valueOf(effect.stringValue);
            } catch (Exception ex) {
                return "Choose a valid status.";
            }
        }
        if (uses(TARGET)) {
            try {
                AbilityEffectTarget.valueOf(effect.target);
            } catch (Exception ex) {
                return "Choose SELF, ENEMY, or BOTH as the effect target.";
            }
        }
        if (uses(TIMING)) {
            try {
                AbilityEffectTiming.valueOf(effect.timing);
            } catch (Exception ex) {
                return "Choose when the status should be applied.";
            }
        }
        if (uses(DURATION)) {
            if (effect.durationRounds == null
                || (effect.durationRounds != -1 && effect.durationRounds < 1)) {
                return "Duration must be -1 (permanent) or at least 1 round.";
            }
            if (AbilityEffectTiming.ROUND_START.name().equals(effect.timing)
                && effect.durationRounds != 1) {
                return "A ROUND_START status must last 1 round so it refreshes without stacking.";
            }
        }
        if (uses(MAGNITUDE) && !isFinite(effect.magnitude)) return "Enter a valid status magnitude.";
        if (uses(USES) && (effect.uses == null || (effect.uses != -1 && effect.uses < 1))) {
            return "Uses must be -1 (unlimited) or at least 1.";
        }
        if (uses(BATTLE_STAT)) {
            try { BattleStatKey.fromString(effect.stringValue); }
            catch (Exception ex) { return "Choose a valid battle stat."; }
        }

        return switch (this) {
            case STAT_ADD, STAT_BONUS_POINTS,
                 MOVE_ACCURACY_ADD, OPPONENT_ACCURACY_ADD,
                  MODIFY_AP_BAR, TEMP_STAT_ADD -> effect.intValue == 0 ? "Enter a non-zero amount." : null;
            case STAT_SET_VALUE, TEMP_STAT_SET_VALUE -> effect.intValue < 0 ? "Stat value cannot be negative." : null;
            case STAT_MULTIPLY, CE_COST_MULTIPLY, MOVE_ACCURACY_MULTIPLY,
                  OPPONENT_ACCURACY_MULTIPLY, DAMAGE_MULTIPLY, MODIFY_DEFENSE,
                  TEMP_STAT_MULTIPLY, BATTLE_STAT_MULTIPLY ->
                effect.doubleValue <= 0 || effect.doubleValue == 1.0
                    ? "Enter a positive multiplier other than 1.0." : null;
            case STAT_DIVIDE -> effect.doubleValue <= 0 || effect.doubleValue == 1.0
                ? "Enter a positive divisor other than 1.0." : null;
            case BF_CHANCE_ADD -> effect.doubleValue == 0.0
                || effect.doubleValue < -1.0 || effect.doubleValue > 1.0
                    ? "Chance change must be non-zero and between -100% and 100%." : null;
            case AUTO_STATUS_APPLY -> effect.magnitude == 0.0
                ? "Enter a non-zero status magnitude." : null;
            case COST_CE_PER_ROUND -> effect.intValue <= 0
                ? "Round-start CE cost must be greater than 0." : null;
            case HEAL_HP, RESTORE_CE, DRAIN_CE, DEAL_DIRECT_DAMAGE, DAMAGE_SHIELD ->
                effect.intValue <= 0 ? "Amount must be greater than 0." : null;
            case HEAL_HP_PERCENT, RESTORE_CE_PERCENT, DRAIN_CE_PERCENT,
                 DEAL_MAX_HP_DAMAGE -> effect.doubleValue <= 0 || effect.doubleValue > 1
                ? "Percentage must be greater than 0% and no more than 100%." : null;
            case BATTLE_STAT_ADD -> effect.doubleValue == 0.0
                ? "Enter a non-zero amount." : null;
            default -> null;
        };
    }

    /** True for effects executed by the passive dispatcher when an ability activates. */
    public boolean isTriggeredRuntimeEffect() {
        return ordinal() >= HEAL_HP.ordinal();
    }

    private static void timedDefaults(AbilityEffectData effect) {
        effect.target = AbilityEffectTarget.SELF.name();
        effect.durationRounds = 1;
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
