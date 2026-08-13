package com.jjktbf.model.character;

import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryResolver;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashSet;

import static com.jjktbf.model.character.AbilityEffectParameter.DECIMAL;
import static com.jjktbf.model.character.AbilityEffectParameter.DURATION;
import static com.jjktbf.model.character.AbilityEffectParameter.INTEGER;
import static com.jjktbf.model.character.AbilityEffectParameter.MAGNITUDE;
import static com.jjktbf.model.character.AbilityEffectParameter.PER_TICK_REMOVAL_CHANCE;
import static com.jjktbf.model.character.AbilityEffectParameter.ABILITY_ID;
import static com.jjktbf.model.character.AbilityEffectParameter.CHARACTER_ID;
import static com.jjktbf.model.character.AbilityEffectParameter.MOVE_ID;
import static com.jjktbf.model.character.AbilityEffectParameter.MOVE_SCOPE;
import static com.jjktbf.model.character.AbilityEffectParameter.STAT;
import static com.jjktbf.model.character.AbilityEffectParameter.STATUS_TYPE;
import static com.jjktbf.model.character.AbilityEffectParameter.TARGET;
import static com.jjktbf.model.character.AbilityEffectParameter.TECHNIQUE;
import static com.jjktbf.model.character.AbilityEffectParameter.TIMING;
import static com.jjktbf.model.character.AbilityEffectParameter.USES;
import static com.jjktbf.model.character.AbilityEffectParameter.BATTLE_STAT;
import static com.jjktbf.model.character.AbilityEffectParameter.CODED_FEATURE;
import static com.jjktbf.model.character.AbilityEffectParameter.CODED_ACTION;

/**
 * Mechanical effects that can be composed into an ability.
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
    STAT_ALLOCATION_MINIMUM(
        "Set allocation minimum",
        "Sets the minimum value assignable to one base stat without affecting battle-time stat changes.",
        STAT, INTEGER),
    STAT_ALLOCATION_MAXIMUM(
        "Set allocation maximum",
        "Sets the maximum value assignable to one base stat without affecting battle-time stat changes.",
        STAT, INTEGER),
    STAT_BONUS_POINTS(
        "Change point budget",
        "Changes the character editor's point-buy budget.",
        INTEGER),

    POISON_IMMUNITY(
        "Poison immunity",
        "Marks the character as immune to poison effects."),
    SOUL_AWARE_ATTACKS(
        "Soul-aware attacks",
        "Marks the character's attacks as capable of interacting with souls."),
    CODED(
        "Coded effect",
        "Enables an allow-listed compiled mechanic that cannot be composed from generic effects.",
        CODED_FEATURE),
    CODED_MOVE_ACTION(
        "Coded move effect",
        "Runs an allow-listed compiled effect primitive when its move trigger fires.",
        CODED_ACTION, TARGET),

    CE_COST_TO_MINIMUM(
        "Minimum CE costs",
        "Forces matching moves to use their configured minimum CE cost.",
        MOVE_SCOPE),
    CE_COST_MULTIPLY(
        "Multiply CE costs",
        "Multiplies the CE cost of matching moves. 0.50 halves the cost.",
        MOVE_SCOPE, DECIMAL),
    CE_COST_ALTER(
        "Alter CE costs",
        "Scales matching move costs, then adds or subtracts a flat CE amount. Applied after the move's configured CE bounds and never below zero.",
        MOVE_SCOPE, DECIMAL, INTEGER),

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
    NEVER_MISS(
        "Never Miss",
        "Gives matching attacks an accuracy-priority tier. Never Miss wins when its tier is equal to or higher than Never Hit.",
        MOVE_SCOPE, INTEGER),
    NEVER_HIT(
        "Never Hit",
        "Gives this character an accuracy-defense tier against matching attacks. It stops only lower-tier Never Miss attacks.",
        MOVE_SCOPE, INTEGER),

    DAMAGE_MULTIPLY(
        "Multiply damage",
        "Multiplies damage dealt by matching moves.",
        MOVE_SCOPE, DECIMAL),
    MOVE_BASE_POWER_MULTIPLY(
        "Multiply move base power",
        "Multiplies the configured base power of matching moves before power and defense are applied.",
        MOVE_SCOPE, DECIMAL),
    INCOMING_DAMAGE_MULTIPLY(
        "Multiply incoming damage",
        "Multiplies damage taken from matching moves. 0.95 reduces it by 5%.",
        MOVE_SCOPE, DECIMAL),
    GRANT_MOVE(
        "Grant move",
        "Adds one move to the character's available moves, bypassing all requirements.",
        MOVE_ID),
    GRANT_ABILITY(
        "Grant ability",
        "Adds one ability to the character's available abilities. It must still be assigned normally.",
        ABILITY_ID),
    UNLOCK_MOVE(
        "Unlock move",
        "Adds one move to the character's available moves. It must still be learned normally.",
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
    DEFENSE_FROM_DURABILITY(
        "Defense from durability",
        "Replaces the normal defense formula with scaled Durability multiplied by this value.",
        DECIMAL),
    MODIFY_AP_BAR(
        "Change AP bar",
        "Adds or subtracts a flat amount from the character's AP bar.",
        INTEGER),
    AUTO_STATUS_APPLY(
        "Apply status automatically",
        "Applies a supported status at fight start, round start, or after a hit.",
        STATUS_TYPE, TARGET, TIMING, DURATION, MAGNITUDE, PER_TICK_REMOVAL_CHANCE),
    LOCK_MOVE_TAG(
        "Lock own move tag",
        "Prevents this character from selecting moves with one tag.",
        MOVE_SCOPE),
    SET_JUJUTSU_ART_SLOTS(
        "Set Jujutsu Art slots",
        "Overrides the number of Jujutsu Art slots available to the character.",
        INTEGER),
    COST_CE_PER_ROUND(
        "Round-start CE cost",
        "Drains CE before planning each round. Other passive effects remain active at 0 CE.",
        INTEGER),
    MAX_ACTIVE_SUMMONS(
        "Maximum active summons",
        "Caps the number of direct active and pending shikigami summons. Multiple caps use the lowest value.",
        INTEGER),
    SUMMON_CE_UPKEEP_PER_ACTIVE_TICK(
        "Summon CE upkeep per active tick",
        "Adds this flat CE upkeep rate to the shikigami while it is actively summoned.",
        DECIMAL),

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
        STATUS_TYPE, TARGET, DURATION, MAGNITUDE, PER_TICK_REMOVAL_CHANCE),
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
        "Adds or subtracts from a character stat for the configured rounds and ticks.",
        STAT, TARGET, INTEGER, DURATION),
    TEMP_STAT_MULTIPLY(
        "Timed character stat multiplier",
        "Multiplies a character stat for the configured rounds and ticks.",
        STAT, TARGET, DECIMAL, DURATION),
    TEMP_STAT_SET_VALUE(
        "Timed character stat set",
        "Sets a character stat to an exact value for the configured rounds and ticks.",
        STAT, TARGET, INTEGER, DURATION),
    TEMP_STAT_PERCENT(
        "Stat percentage",
        "Adds or subtracts a percentage of the scaled character stat for the configured rounds and ticks. Percentage effects stack additively with each other.",
        STAT, TARGET, DECIMAL, DURATION),
    BATTLE_STAT_ADD(
        "Timed battle stat change",
        "Adds to a derived battle value for the configured rounds and ticks.",
        BATTLE_STAT, TARGET, DECIMAL, DURATION),
    BATTLE_STAT_MULTIPLY(
        "Timed battle stat multiplier",
        "Multiplies a derived battle value for the configured rounds and ticks.",
        BATTLE_STAT, TARGET, DECIMAL, DURATION),
    BATTLE_STAT_PERCENT(
        "Battle stat percentage",
        "Adds or subtracts a percentage of the scaled derived battle value for the configured rounds and ticks. Percentage effects stack additively with each other.",
        BATTLE_STAT, TARGET, DECIMAL, DURATION),
    BATTLE_STAT_ODDS_MULTIPLY(
        "Multiply battle-stat odds",
        "Permanently multiplies the odds of a probability battle stat. A factor of 2 doubles odds without directly doubling probability.",
        BATTLE_STAT, DECIMAL),

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
    STUN_CURRENT_ACTION(
        "Stun current action",
        "Cancels the target's active, not-yet-fired action on the current tick. HEAVY actions resist this effect.",
        TARGET),
    TEMP_LOCK_MOVE_TAG(
        "Temporarily lock move tag",
        "Prevents the target from planning moves with one tag for the duration.",
        TARGET, MOVE_SCOPE, DURATION),
    MOVE_UNAVAILABLE_WHILE_OWNED_SUMMON_ACTIVE(
        "Block while shikigami is active",
        "Prevents this move from being used while the selected owned shikigami is active on the field.",
        CHARACTER_ID),
    SUMMON_CHARACTER(
        "Summon character",
        "Summons a shikigami combatant onto the owner's team when activated.",
        CHARACTER_ID),
    DESUMMON_OWNED_SHIKIGAMI(
        "Desummon owned shikigami",
        "Voluntarily dismisses every direct shikigami summon owned by this combatant and their descendants."),
    DESUMMON_TARGET_SHIKIGAMI(
        "Desummon target shikigami",
        "Voluntarily dismisses the targeted shikigami combatant.",
        TARGET);

    /**
     * Effects that require an active ability condition to run at battle time.
     * This explicit set replaces the former ordinal-based check so adding a new
     * activation-required effect (e.g. SUMMON_CHARACTER) does not silently shift
     * the ordinal boundary and reclassify earlier effects.
     */
    private static final java.util.Set<AbilityEffectType> ACTIVATION_REQUIRED =
        java.util.EnumSet.of(
            HEAL_HP, HEAL_HP_PERCENT, RESTORE_CE, RESTORE_CE_PERCENT,
            DRAIN_CE, DRAIN_CE_PERCENT, DEAL_DIRECT_DAMAGE, DEAL_MAX_HP_DAMAGE,
            INSTANT_KILL, APPLY_STATUS, REMOVE_STATUS, CLEAR_STATUSES,
            TEMP_STAT_ADD, TEMP_STAT_MULTIPLY, TEMP_STAT_SET_VALUE, TEMP_STAT_PERCENT,
            BATTLE_STAT_ADD, BATTLE_STAT_MULTIPLY, BATTLE_STAT_PERCENT,
            IGNORE_DAMAGE, DAMAGE_SHIELD, SURVIVE_FATAL_DAMAGE,
            GUARANTEE_NEXT_HIT, GUARANTEE_NEXT_DODGE, GUARANTEE_NEXT_BLACK_FLASH,
            CANCEL_NEXT_MOVE, STUN_CURRENT_ACTION, TEMP_LOCK_MOVE_TAG, SUMMON_CHARACTER,
            DESUMMON_OWNED_SHIKIGAMI, DESUMMON_TARGET_SHIKIGAMI,
            CODED_MOVE_ACTION);

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

    /** Create a move attachment using this same immutable effect primitive. */
    public com.jjktbf.model.move.MoveEffectData createDefaultMoveEffect() {
        com.jjktbf.model.move.MoveEffectData effect =
            new com.jjktbf.model.move.MoveEffectData();
        reset(effect);
        return effect;
    }

    /** Replace all effect parameters with this type's defaults. */
    public void reset(AbilityEffectData effect) {
        effect.type = name();
        effect.codedAbilityKey = null;
        effect.codedFeature = null;
        effect.codedAction = null;
        effect.codedTarget = null;
        effect.codedStackCount = null;
        effect.codedParameters = null;
        effect.stat = null;
        effect.intValue = null;
        effect.doubleValue = null;
        effect.moveTag = null;
        effect.moveId = null;
        effect.abilityId = null;
        effect.characterId = null;
        effect.stringValue = null;
        effect.target = null;
        effect.timing = null;
        effect.durationRounds = null;
        effect.durationTicks = null;
        effect.magnitude = null;
        effect.perTickRemovalChance = null;
        effect.uses = null;
        effect.masteryProgression = null;

        if (uses(STAT)) effect.stat = StatKey.VITALITY.fieldName;
        if (uses(TARGET)) effect.target = AbilityEffectTarget.SELF.name();
        if (uses(DURATION)) effect.durationTicks = 0;

        switch (this) {
            case CODED -> {
                CodedAbilityRegistry.AbilityFeature feature =
                    CodedAbilityRegistry.abilityFeatures().get(0);
                effect.codedAbilityKey = feature.key();
                effect.codedFeature = feature.feature();
            }
            case CODED_MOVE_ACTION -> {
                CodedAbilityRegistry.EffectAction action =
                    CodedAbilityRegistry.effectActions().get(0);
                effect.codedAbilityKey = action.key();
                effect.codedAction = action.action();
                CodedAbilityRegistry.prepareMoveEffect(effect);
            }
            case STAT_ADD -> effect.intValue = 10;
            case STAT_MULTIPLY -> effect.doubleValue = 1.10;
            case STAT_DIVIDE -> effect.doubleValue = 2.0;
            case STAT_SET_VALUE -> effect.intValue = CharacterStats.BASELINE;
            case STAT_ALLOCATION_MINIMUM, STAT_ALLOCATION_MAXIMUM ->
                effect.intValue = CharacterStats.BASELINE;
            case STAT_BONUS_POINTS -> effect.intValue = 10;
            case CE_COST_ALTER -> {
                effect.doubleValue = 0.50;
                effect.intValue = 0;
            }
            case CE_COST_MULTIPLY, MOVE_ACCURACY_MULTIPLY,
                 OPPONENT_ACCURACY_MULTIPLY, DAMAGE_MULTIPLY, MOVE_BASE_POWER_MULTIPLY,
                 INCOMING_DAMAGE_MULTIPLY, MODIFY_DEFENSE -> effect.doubleValue = 1.10;
            case DEFENSE_FROM_DURABILITY -> effect.doubleValue = 4.0 / 3.0;
            case MOVE_ACCURACY_ADD, OPPONENT_ACCURACY_ADD -> effect.intValue = 10;
            case NEVER_MISS, NEVER_HIT -> effect.intValue = 1;
            case BF_CHANCE_ADD -> effect.doubleValue = 0.05;
            case MODIFY_AP_BAR -> effect.intValue = 10;
            case AUTO_STATUS_APPLY -> {
                effect.stringValue = StatusEffectType.STRENGTH_INCREASE.name();
                effect.target = AbilityEffectTarget.SELF.name();
                effect.timing = AbilityEffectTiming.FIGHT_START.name();
                effect.durationRounds = -1;
                effect.magnitude = 10.0;
            }
            case LOCK_MOVE_TAG -> effect.moveTag = MoveTag.PHYSICAL.name();
            case SET_JUJUTSU_ART_SLOTS -> effect.intValue = 0;
            case COST_CE_PER_ROUND -> effect.intValue = 5;
            case MAX_ACTIVE_SUMMONS -> effect.intValue = 1;
            case SUMMON_CE_UPKEEP_PER_ACTIVE_TICK -> effect.doubleValue = 0.1;
            case HEAL_HP, RESTORE_CE, DRAIN_CE, DEAL_DIRECT_DAMAGE -> effect.intValue = 10;
            case HEAL_HP_PERCENT, RESTORE_CE_PERCENT, DRAIN_CE_PERCENT,
                 DEAL_MAX_HP_DAMAGE -> effect.doubleValue = 0.10;
            case APPLY_STATUS -> {
                effect.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
                effect.target = AbilityEffectTarget.ENEMY.name();
                effect.durationRounds = 1;
                effect.magnitude = 10.0;
            }
            case REMOVE_STATUS -> {
                effect.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
                effect.target = AbilityEffectTarget.SELF.name();
            }
            case CLEAR_STATUSES, INSTANT_KILL, DESUMMON_TARGET_SHIKIGAMI ->
                effect.target = AbilityEffectTarget.ENEMY.name();
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
            case TEMP_STAT_PERCENT -> {
                effect.doubleValue = 0.20;
                timedDefaults(effect);
            }
            case BATTLE_STAT_PERCENT -> {
                effect.stringValue = BattleStatKey.ACCURACY.name();
                effect.doubleValue = 0.20;
                timedDefaults(effect);
            }
            case BATTLE_STAT_ODDS_MULTIPLY -> {
                effect.stringValue = BattleStatKey.BLACK_FLASH_CHANCE.name();
                effect.doubleValue = 2.0;
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
            case SUMMON_CHARACTER -> {
                // No target needed — the summon joins the owner's team.
                effect.characterId = null;
            }
            default -> { }
        }
    }

    /** Fill missing relevant values and discard fields belonging to another type. */
    public void prepare(AbilityEffectData effect) {
        AbilityEffectData defaults = createDefault();
        effect.type = name();
        if (uses(STATUS_TYPE) && uses(MAGNITUDE) && !isBlank(effect.stringValue)) {
            String storedType = effect.stringValue;
            try {
                StatusEffectType status = StatusEffectType.fromName(
                    storedType, effect.magnitude != null ? effect.magnitude : 0.0);
                effect.stringValue = status.name();
                if (effect.magnitude != null) {
                    effect.magnitude = StatusEffectType.normalizeStoredMagnitude(
                        storedType, effect.magnitude);
                }
            } catch (IllegalArgumentException ignored) { }
        }
        clearUnusedFields(effect);
        if (uses(CODED_FEATURE) && (isBlank(effect.codedAbilityKey)
            || isBlank(effect.codedFeature))) {
            effect.codedAbilityKey = defaults.codedAbilityKey;
            effect.codedFeature = defaults.codedFeature;
        }
        if (uses(CODED_FEATURE)) CodedAbilityRegistry.prepareAbilityParameters(effect);
        if (uses(CODED_ACTION) && (isBlank(effect.codedAbilityKey)
            || isBlank(effect.codedAction))) {
            effect.codedAbilityKey = defaults.codedAbilityKey;
            effect.codedAction = defaults.codedAction;
        }
        if (uses(CODED_ACTION)) CodedAbilityRegistry.prepareMoveEffect(effect);
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
        if (uses(DURATION) && effect.durationTicks == null) effect.durationTicks = defaults.durationTicks;
        if (uses(MAGNITUDE) && effect.magnitude == null) effect.magnitude = defaults.magnitude;
        if (uses(PER_TICK_REMOVAL_CHANCE) && effect.perTickRemovalChance == null) {
            effect.perTickRemovalChance = defaultPerTickRemovalChance(effect.stringValue);
        }
        if (uses(STATUS_TYPE) && uses(MAGNITUDE)) {
            try {
                if (!StatusEffectType.fromName(effect.stringValue).usesMagnitude()) {
                    effect.magnitude = 0.0;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        if (uses(USES) && effect.uses == null) effect.uses = defaults.uses;
        if (uses(BATTLE_STAT) && isBlank(effect.stringValue)) effect.stringValue = defaults.stringValue;
    }

    /** Remove stale values so persisted JSON contains only parameters this type reads. */
    public void clearUnusedFields(AbilityEffectData effect) {
        if (!uses(CODED_FEATURE)) {
            effect.codedFeature = null;
            if (!uses(CODED_ACTION)) effect.codedAbilityKey = null;
        }
        if (!uses(CODED_ACTION)) {
            effect.codedAction = null;
            effect.codedTarget = null;
            effect.codedStackCount = null;
            if (!uses(CODED_FEATURE)) effect.codedParameters = null;
        }
        if (!uses(STAT)) effect.stat = null;
        if (!uses(INTEGER)) effect.intValue = null;
        if (!uses(DECIMAL)) effect.doubleValue = null;
        if (!uses(MOVE_SCOPE)) effect.moveTag = null;
        if (!uses(MOVE_ID)) effect.moveId = null;
        if (!uses(ABILITY_ID)) effect.abilityId = null;
        if (!uses(CHARACTER_ID)) effect.characterId = null;
        if (!uses(TECHNIQUE) && !uses(STATUS_TYPE) && !uses(BATTLE_STAT)) effect.stringValue = null;
        if (!uses(TARGET)) effect.target = null;
        if (!uses(TIMING)) effect.timing = null;
        if (!uses(DURATION)) {
            effect.durationRounds = null;
            effect.durationTicks = null;
        }
        if (!uses(MAGNITUDE)) effect.magnitude = null;
        if (!uses(PER_TICK_REMOVAL_CHANCE)) effect.perTickRemovalChance = null;
        if (!uses(USES)) effect.uses = null;
        if (!uses(BATTLE_STAT) && !uses(TECHNIQUE) && !uses(STATUS_TYPE)) effect.stringValue = null;
        Set<String> allowedProgressions = masteryProgressionFields(effect);
        if (effect.masteryProgression != null) {
            effect.masteryProgression = effect.masteryProgression.entrySet().stream()
                .filter(entry -> allowedProgressions.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(
                    java.util.Map.Entry::getKey,
                    java.util.Map.Entry::getValue,
                    (left, right) -> left,
                    java.util.LinkedHashMap::new));
            if (effect.masteryProgression.isEmpty()) effect.masteryProgression = null;
        }
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
        if (uses(MOVE_ID) && isBlank(effect.moveId)) return "Choose a move.";
        if (uses(ABILITY_ID) && isBlank(effect.abilityId)) return "Choose an ability to grant.";
        if (uses(CHARACTER_ID) && isBlank(effect.characterId)) {
            return this == SUMMON_CHARACTER
                ? "Choose a shikigami to summon." : "Choose a shikigami.";
        }
        if (uses(TECHNIQUE) && isBlank(effect.stringValue)) return "Choose a technique.";

        StatusEffectType status = null;
        if (uses(STATUS_TYPE)) {
            try {
                status = StatusEffectType.fromName(effect.stringValue);
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
            if (effect.durationRounds == null) return "Enter a round duration.";
            int ticks = effect.durationTicks != null ? effect.durationTicks : 0;
            try {
                StatusEffect.validateDuration(status, effect.durationRounds, ticks);
            } catch (IllegalArgumentException ignored) {
                return status != null && status.requiresTickDuration()
                    ? "Stagger must use 0 rounds and at least 1 AP tick."
                    : status != null && status.requiresRoundDuration()
                        ? "Poison must use a positive round duration or be permanent, with 0 AP ticks."
                    : "Use -1 rounds and 0 ticks for permanent, or enter at least one round or tick.";
            }
            if (AbilityEffectTiming.ROUND_START.name().equals(effect.timing)
                && (status == null || !status.requiresTickDuration())
                && (effect.durationRounds != 1 || ticks != 0)) {
                return "A ROUND_START status must last exactly 1 round and 0 ticks so it refreshes without stacking.";
            }
        }
        if (uses(MAGNITUDE) && (!isFinite(effect.magnitude) || effect.magnitude < 0)) {
            return "Enter a non-negative status amount.";
        }
        if (uses(PER_TICK_REMOVAL_CHANCE) && effect.perTickRemovalChance != null
            && (!isFinite(effect.perTickRemovalChance)
                || effect.perTickRemovalChance < 0.0 || effect.perTickRemovalChance > 1.0)) {
            return "Per-tick removal chance must be between 0% and 100%.";
        }
        if (uses(USES) && (effect.uses == null || (effect.uses != -1 && effect.uses < 1))) {
            return "Uses must be -1 (unlimited) or at least 1.";
        }
        if (uses(BATTLE_STAT)) {
            try {
                BattleStatKey stat = BattleStatKey.fromString(effect.stringValue);
                if (this == BATTLE_STAT_ODDS_MULTIPLY && !stat.isProbability()) {
                    return "Choose a probability battle stat.";
                }
            }
            catch (Exception ex) { return "Choose a valid battle stat."; }
        }
        if (uses(CODED_FEATURE) && !CodedAbilityRegistry.supportsAbilityEffect(
            effect.codedAbilityKey, effect.codedFeature)) {
            return "Choose a supported coded effect.";
        }
        if (uses(CODED_FEATURE)) {
            String codedParameterError =
                CodedAbilityRegistry.abilityParameterValidationError(effect);
            if (codedParameterError != null) return codedParameterError;
        }
        if (uses(CODED_ACTION) && !CodedAbilityRegistry.supportsEffect(
            effect.codedAbilityKey, effect.codedAction,
            effect.codedTarget, effect.codedStackCount)) {
            return "Choose a supported coded move effect.";
        }
        if (uses(CODED_ACTION)) {
            String codedParameterError = CodedAbilityRegistry.effectParameterValidationError(
                effect.codedAbilityKey, effect.codedAction,
                effect.codedTarget, effect.codedParameters);
            if (codedParameterError != null) return codedParameterError;
        }

        String literalError = switch (this) {
            case STAT_ADD, STAT_BONUS_POINTS,
                 MOVE_ACCURACY_ADD, OPPONENT_ACCURACY_ADD,
                  MODIFY_AP_BAR, TEMP_STAT_ADD -> effect.intValue == 0 ? "Enter a non-zero amount." : null;
            case STAT_SET_VALUE, TEMP_STAT_SET_VALUE -> effect.intValue < 0 ? "Stat value cannot be negative." : null;
            case STAT_ALLOCATION_MINIMUM, STAT_ALLOCATION_MAXIMUM ->
                effect.intValue < CharacterStats.MIN_STAT || effect.intValue > CharacterStats.MAX_STAT
                    ? "Allocation bound must be between " + CharacterStats.MIN_STAT
                        + " and " + CharacterStats.MAX_STAT + "."
                    : null;
            case STAT_MULTIPLY, CE_COST_MULTIPLY, MOVE_ACCURACY_MULTIPLY,
                  OPPONENT_ACCURACY_MULTIPLY, DAMAGE_MULTIPLY, MOVE_BASE_POWER_MULTIPLY,
                  INCOMING_DAMAGE_MULTIPLY, MODIFY_DEFENSE, DEFENSE_FROM_DURABILITY,
                  TEMP_STAT_MULTIPLY, BATTLE_STAT_MULTIPLY, BATTLE_STAT_ODDS_MULTIPLY ->
                effect.doubleValue <= 0 || effect.doubleValue == 1.0
                    ? "Enter a positive multiplier other than 1.0." : null;
            case TEMP_STAT_PERCENT, BATTLE_STAT_PERCENT -> effect.doubleValue == 0
                    || effect.doubleValue <= -1.0
                        ? "Enter a non-zero percentage greater than -100%." : null;
            case CE_COST_ALTER -> effect.doubleValue < 0
                || (effect.doubleValue == 1.0 && effect.intValue == 0)
                    ? "Use a non-negative multiplier or a non-zero CE change."
                    : null;
            case STAT_DIVIDE -> effect.doubleValue <= 0 || effect.doubleValue == 1.0
                ? "Enter a positive divisor other than 1.0." : null;
            case BF_CHANCE_ADD -> effect.doubleValue == 0.0
                || effect.doubleValue < -1.0 || effect.doubleValue > 1.0
                    ? "Chance change must be non-zero and between -100% and 100%." : null;
            case AUTO_STATUS_APPLY -> status != null && status.usesMagnitude() && effect.magnitude == 0.0
                ? "Enter a non-zero status magnitude." : null;
            case COST_CE_PER_ROUND -> effect.intValue <= 0
                ? "Round-start CE cost must be greater than 0." : null;
            case MAX_ACTIVE_SUMMONS -> effect.intValue <= 0
                ? "Maximum active summons must be greater than 0." : null;
            case SUMMON_CE_UPKEEP_PER_ACTIVE_TICK -> effect.doubleValue <= 0
                ? "Summon upkeep must be greater than 0." : null;
            case SET_JUJUTSU_ART_SLOTS -> effect.intValue < 0
                || effect.intValue > CombatStats.MAX_ART_SLOTS
                ? "Jujutsu Art slots must be between 0 and "
                    + CombatStats.MAX_ART_SLOTS + "." : null;
            case NEVER_MISS, NEVER_HIT -> effect.intValue < 1 || effect.intValue > 5
                ? "Accuracy priority tier must be between 1 and 5." : null;
            case HEAL_HP, RESTORE_CE, DRAIN_CE, DEAL_DIRECT_DAMAGE, DAMAGE_SHIELD ->
                effect.intValue <= 0 ? "Amount must be greater than 0." : null;
            case HEAL_HP_PERCENT, RESTORE_CE_PERCENT, DRAIN_CE_PERCENT,
                 DEAL_MAX_HP_DAMAGE -> effect.doubleValue <= 0 || effect.doubleValue > 1
                ? "Percentage must be greater than 0% and no more than 100%." : null;
            case BATTLE_STAT_ADD -> effect.doubleValue == 0.0
                ? "Enter a non-zero amount." : null;
            default -> null;
        };
        if (literalError != null) return literalError;

        String progressionError = TechniqueMasteryProgressions.validationError(
            effect.masteryProgression, masteryProgressionFields(effect));
        if (progressionError != null) return progressionError;
        if (effect.masteryProgression != null && !effect.masteryProgression.isEmpty()) {
            for (int mastery = 0; mastery <= CharacterStats.MAX_STAT; mastery++) {
                AbilityEffectData resolved;
                try {
                    resolved = TechniqueMasteryResolver.resolve(effect, mastery);
                } catch (RuntimeException exception) {
                    return "Invalid mastery progression at CTM " + mastery + ": "
                        + exception.getMessage();
                }
                resolved.masteryProgression = null;
                String error = validationError(resolved);
                if (error != null) {
                    return "At CTM " + mastery + ": " + error;
                }
            }
        }
        return null;
    }

    /** Numeric fields that may derive their value from CTM for this effect row. */
    public Set<String> masteryProgressionFields(AbilityEffectData effect) {
        if (this == STAT_ALLOCATION_MINIMUM || this == STAT_ALLOCATION_MAXIMUM
            || this == STAT_BONUS_POINTS || this == SET_JUJUTSU_ART_SLOTS) return Set.of();
        Set<String> fields = new LinkedHashSet<>();
        if (uses(INTEGER)) fields.add(TechniqueMasteryProgressions.INT_VALUE);
        if (uses(DECIMAL)) fields.add(TechniqueMasteryProgressions.DOUBLE_VALUE);
        if (uses(DURATION)) {
            fields.add(TechniqueMasteryProgressions.DURATION_ROUNDS);
            fields.add(TechniqueMasteryProgressions.DURATION_TICKS);
        }
        if (uses(MAGNITUDE)) fields.add(TechniqueMasteryProgressions.MAGNITUDE);
        if (uses(PER_TICK_REMOVAL_CHANCE)) {
            fields.add(TechniqueMasteryProgressions.PER_TICK_REMOVAL_CHANCE);
        }
        if (uses(USES)) fields.add(TechniqueMasteryProgressions.USES);
        if (uses(CODED_FEATURE) && effect != null && effect.codedParameters != null) {
            fields.addAll(effect.codedParameters.keySet());
        }
        if (uses(CODED_ACTION) && effect != null) {
            if (effect.codedStackCount != null) {
                fields.add(TechniqueMasteryProgressions.CODED_STACK_COUNT);
            }
            if (effect.codedParameters != null) fields.addAll(effect.codedParameters.keySet());
        }
        return Collections.unmodifiableSet(fields);
    }

    /** True when an effect needs an active ability condition to run at battle time. */
    public boolean requiresActivation() {
        return ACTIVATION_REQUIRED.contains(this);
    }

    /** True for effects resolved while a passive ability is assigned. */
    public boolean isPassiveOnly() {
        return switch (this) {
            case STAT_ALLOCATION_MINIMUM, STAT_ALLOCATION_MAXIMUM, STAT_BONUS_POINTS,
                  POISON_IMMUNITY, SOUL_AWARE_ATTACKS,
                   GRANT_MOVE, GRANT_ABILITY, UNLOCK_MOVE,
                   UNLOCK_TECHNIQUE, AUTO_STATUS_APPLY, DEFENSE_FROM_DURABILITY,
                   SET_JUJUTSU_ART_SLOTS, MAX_ACTIVE_SUMMONS,
                   SUMMON_CE_UPKEEP_PER_ACTIVE_TICK, NEVER_MISS, NEVER_HIT,
                   BATTLE_STAT_ODDS_MULTIPLY -> true;
            default -> false;
        };
    }

    /** Effect primitives that may be activated from a move effect row. */
    public boolean isMoveEffect() {
        return requiresActivation() || isAccuracyPriority() || isMoveAvailabilityConstraint();
    }

    /** Move constraints queried before placement and again when the move fires. */
    public boolean isMoveAvailabilityConstraint() {
        return this == MOVE_UNAVAILABLE_WHILE_OWNED_SUMMON_ACTIVE;
    }

    /** Accuracy-priority primitives are queried during hit resolution rather than fired. */
    public boolean isAccuracyPriority() {
        return this == NEVER_MISS || this == NEVER_HIT;
    }

    public boolean isMoveOnly() {
        return this == CODED_MOVE_ACTION || isMoveAvailabilityConstraint();
    }

    private static void timedDefaults(AbilityEffectData effect) {
        effect.target = AbilityEffectTarget.SELF.name();
        effect.durationRounds = 1;
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static double defaultPerTickRemovalChance(String statusName) {
        try {
            return StatusEffectType.fromName(statusName).defaultPerTickRemovalChance();
        } catch (IllegalArgumentException ignored) {
            return 0.0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
