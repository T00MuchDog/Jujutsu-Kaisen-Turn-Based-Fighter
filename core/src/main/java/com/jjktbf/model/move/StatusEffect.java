package com.jjktbf.model.move;

import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;

import java.util.Map;

/**
 * An instance of a status effect as it exists on a combatant mid-battle.
 * Immutable descriptor — the combat engine tracks duration countdown separately.
 *
 * <p>An effect row is exactly one of three flavours:
 * <ul>
 *   <li><b>Status effect</b> — carries a non-null {@link StatusEffectType} and is
 *       applied to a combatant for a duration. Most modify stats; some, such as
 *       {@link StatusEffectType#STAGGER}, have gameplay behavior instead.</li>
 *   <li><b>Coded action</b> — carries a {@code codedAbilityKey} + {@code codedAction}
 *       pair (validated by {@link com.jjktbf.model.character.coded.CodedAbilityRegistry})
 *       and is dispatched to the matching {@link com.jjktbf.model.character.coded.CodedAbilityRuntime}
 *       instead of being applied as a status. This is how a technique move's
 *       hardcoded effect is expressed as an editable, add/remove-able effect row
 *       (self or on-hit) rather than as state baked onto the {@code Move}.</li>
 *   <li><b>Summon</b> — carries a {@code summonCharacterId} (a shikigami
 *       definition id) and enqueues a shikigami onto the wielder's team when the
 *       row fires. This is how a move's shikigami summoning is expressed as an
 *       editable effect row (self / on-hit / defense) rather than as a dedicated
 *       flag on the {@code Move}.</li>
 * </ul>
 * The three flavours are mutually exclusive: a summon row leaves {@code type}
 * null and the coded fields blank, a coded action leaves {@code type} null and
 * the summon id blank, and a status effect leaves the coded and summon fields
 * blank.
 */
public class StatusEffect {

    private final StatusEffectType type;

    /** How many rounds the effect lasts. -1 = until explicitly cleared. */
    private final int durationRounds;

    /** AP ticks after the configured rounds have elapsed. */
    private final int durationTicks;

    /** Magnitude of a stat-modifying effect. Non-stat statuses store zero. */
    private final double magnitude;

    /** Chance to remove this status at each resolution tick. */
    private final double perTickRemovalChance;

    /** Coded-ability key for an effect that cannot be expressed as a status. Blank for status effects. */
    private final String codedAbilityKey;

    /** Action interpreted by {@link #codedAbilityKey} when this effect fires. Blank for status effects. */
    private final String codedAction;

    /** Coded action's configurable target/mode. Blank when the action has no target setting. */
    private final String codedTarget;

    /** Number of Ratio stacks created by a configured coded action. */
    private final Integer codedStackCount;

    /** Allow-listed integer parameters owned by a coded action. */
    private final Map<String, Integer> codedParameters;

    /** Optional per-field CTM formulas or benchmark tables. */
    private final Map<String, TechniqueMasteryProgressionData> masteryProgression;

    /**
     * Shikigami character-definition id summoned when this row fires. Only set on
     * a summon-flavour row (see {@link #isSummon()}); null for status and coded
     * rows. Only {@code CharacterType.SHIKIGAMI} definitions may be referenced.
     */
    private final String summonCharacterId;

    public StatusEffect(StatusEffectType type, int durationRounds, double magnitude) {
        this(type, durationRounds, 0, magnitude);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude
    ) {
        this(type, durationRounds, durationTicks, magnitude,
            defaultPerTickRemovalChance(type),
            null, null, null, null, null, null, null);
    }

    /** Construct a status with an explicit per-resolution-tick removal chance. */
    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        double perTickRemovalChance
    ) {
        this(type, durationRounds, durationTicks, magnitude, perTickRemovalChance,
            null, null, null, null, null, null, null);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        Map<String, TechniqueMasteryProgressionData> masteryProgression
    ) {
        this(type, durationRounds, durationTicks, magnitude,
            defaultPerTickRemovalChance(type),
            null, null, null, null, null, masteryProgression, null);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        double perTickRemovalChance,
        Map<String, TechniqueMasteryProgressionData> masteryProgression
    ) {
        this(type, durationRounds, durationTicks, magnitude, perTickRemovalChance,
            null, null, null, null, null, masteryProgression, null);
    }

    /**
     * Construct a coded-action effect row. {@code type} may be null when a coded
     * ability key is supplied; the combat engine dispatches the row to the matching
     * coded runtime instead of applying it as a status.
     */
    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        String codedAbilityKey,
        String codedAction
    ) {
        this(type, durationRounds, durationTicks, magnitude,
            0.0, codedAbilityKey, codedAction, null, null, null, null, null);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        String codedAbilityKey,
        String codedAction,
        String codedTarget,
        Integer codedStackCount
    ) {
        this(type, durationRounds, durationTicks, magnitude,
            0.0, codedAbilityKey, codedAction, codedTarget, codedStackCount, null, null, null);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        String codedAbilityKey,
        String codedAction,
        String codedTarget,
        Integer codedStackCount,
        Map<String, Integer> codedParameters,
        Map<String, TechniqueMasteryProgressionData> masteryProgression
    ) {
        this(type, durationRounds, durationTicks, magnitude, 0.0,
            codedAbilityKey, codedAction, codedTarget, codedStackCount,
            codedParameters, masteryProgression, null);
    }

    /**
     * Construct a summon effect row. The supplied shikigami character-definition
     * id is enqueued onto the wielder's team when this row fires; the shared
     * runtime summon path materializes it after the current tick batch. Only
     * {@code CharacterType.SHIKIGAMI} definitions may be referenced.
     */
    public StatusEffect(String summonCharacterId) {
        this(null, 0, 0, 0, 0.0,
            null, null, null, null, null, null, summonCharacterId);
    }

    private StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude,
        double perTickRemovalChance,
        String codedAbilityKey,
        String codedAction,
        String codedTarget,
        Integer codedStackCount,
        Map<String, Integer> codedParameters,
        Map<String, TechniqueMasteryProgressionData> masteryProgression,
        String summonCharacterId
    ) {
        boolean coded = codedAbilityKey != null && !codedAbilityKey.isBlank();
        boolean summon = summonCharacterId != null && !summonCharacterId.isBlank();
        if (summon) {
            if (coded) {
                throw new IllegalArgumentException("A summon effect row cannot also be a coded action");
            }
            if (type != null) {
                throw new IllegalArgumentException("A summon effect row cannot also carry a status type");
            }
        } else if (type == null && !coded) {
            throw new IllegalArgumentException("Status effect type is required for a non-coded effect");
        }
        if (!coded && !summon) {
            validateDuration(type, durationRounds, durationTicks);
            if (!Double.isFinite(magnitude) || magnitude < 0) {
                throw new IllegalArgumentException("Status effect amount must be a non-negative number");
            }
            if (!Double.isFinite(perTickRemovalChance)
                || perTickRemovalChance < 0.0 || perTickRemovalChance > 1.0) {
                throw new IllegalArgumentException(
                    "Status effect per-tick removal chance must be between 0% and 100%");
            }
        }
        this.type            = type;
        this.durationRounds  = durationRounds;
        this.durationTicks   = durationTicks;
        this.magnitude       = (!coded && !summon && !type.usesMagnitude()) ? 0.0 : magnitude;
        this.perTickRemovalChance = !coded && !summon ? perTickRemovalChance : 0.0;
        this.codedAbilityKey = codedAbilityKey;
        this.codedAction     = codedAction;
        this.codedTarget     = codedTarget;
        this.codedStackCount = codedStackCount;
        this.codedParameters = codedParameters == null ? Map.of() : Map.copyOf(codedParameters);
        Map<String, TechniqueMasteryProgressionData> copiedProgression =
            TechniqueMasteryProgressions.copy(masteryProgression);
        this.masteryProgression = copiedProgression == null
            ? Map.of() : Map.copyOf(copiedProgression);
        this.summonCharacterId = summon ? summonCharacterId : null;
    }

    /** Build a coded-action effect row bound to the given ability key/action. */
    public static StatusEffect coded(String codedAbilityKey, String codedAction) {
        return new StatusEffect(null, 0, 0, 0, codedAbilityKey, codedAction);
    }

    /** Build a coded-action effect row with editable action-specific settings. */
    public static StatusEffect coded(
        String codedAbilityKey,
        String codedAction,
        String codedTarget,
        Integer codedStackCount
    ) {
        return new StatusEffect(null, 0, 0, 0,
            codedAbilityKey, codedAction, codedTarget, codedStackCount);
    }

    public static StatusEffect coded(
        String codedAbilityKey,
        String codedAction,
        String codedTarget,
        Integer codedStackCount,
        Map<String, Integer> codedParameters,
        Map<String, TechniqueMasteryProgressionData> masteryProgression
    ) {
        return new StatusEffect(null, 0, 0, 0,
            codedAbilityKey, codedAction, codedTarget, codedStackCount,
            codedParameters, masteryProgression);
    }

    /** Create a round-duration poison descriptor for future poison resolution. */
    public static StatusEffect poison(int rounds, double damagePerRound) {
        return new StatusEffect(StatusEffectType.POISON, rounds, 0, damagePerRound);
    }

    public static void validateDuration(int rounds, int ticks) {
        boolean permanent = rounds == -1 && ticks == 0;
        boolean timed = rounds >= 0 && rounds < Integer.MAX_VALUE
            && ticks >= 0 && (rounds > 0 || ticks > 0);
        if (!permanent && !timed) {
            throw new IllegalArgumentException(
                "Status effect duration must be permanent or contain at least one round or tick");
        }
    }

    /** Validate a duration, including any timing restrictions owned by a status type. */
    public static void validateDuration(StatusEffectType type, int rounds, int ticks) {
        validateDuration(rounds, ticks);
        if (type != null && type.requiresTickDuration() && (rounds != 0 || ticks <= 0)) {
            throw new IllegalArgumentException(type.displayName()
                + " must last for at least one AP tick and cannot use round duration");
        }
        if (type != null && type.requiresRoundDuration()
            && ((rounds <= 0 && rounds != -1) || ticks != 0)) {
            throw new IllegalArgumentException(type.displayName()
                + " must use a positive round duration or be permanent");
        }
    }

    public StatusEffectType getType()     { return type; }
    public int getDurationRounds()        { return durationRounds; }
    public int getDurationTicks()         { return durationTicks; }
    public double getMagnitude()          { return magnitude; }
    public double getPerTickRemovalChance() { return perTickRemovalChance; }
    public String getCodedAbilityKey()    { return codedAbilityKey; }
    public String getCodedAction()        { return codedAction; }
    public String getCodedTarget()        { return codedTarget; }
    public Integer getCodedStackCount()   { return codedStackCount; }
    public Map<String, Integer> getCodedParameters() { return codedParameters; }
    public Map<String, TechniqueMasteryProgressionData> getMasteryProgression() {
        return masteryProgression;
    }

    /** True when this row carries a coded action (and is therefore not a status effect). */
    public boolean isCoded() {
        return codedAbilityKey != null && !codedAbilityKey.isBlank();
    }

    /**
     * Shikigami definition id this row summons when it fires, or null for a
     * status or coded row.
     */
    public String getSummonCharacterId() { return summonCharacterId; }

    /** True when this row is a summon effect (enqueues a shikigami when it fires). */
    public boolean isSummon() {
        return summonCharacterId != null && !summonCharacterId.isBlank();
    }

    private static double defaultPerTickRemovalChance(StatusEffectType type) {
        return type == null ? 0.0 : type.defaultPerTickRemovalChance();
    }

    @Override
    public String toString() {
        if (isSummon()) {
            return String.format("StatusEffect{SUMMON %s}", summonCharacterId);
        }
        if (isCoded()) {
            return String.format("StatusEffect{CODED %s/%s target=%s stacks=%s}",
                codedAbilityKey, codedAction, codedTarget, codedStackCount);
        }
        return type.usesMagnitude()
            ? String.format("StatusEffect{%s rounds=%d ticks=%d mag=%.2f}",
                type, durationRounds, durationTicks, magnitude)
            : String.format("StatusEffect{%s rounds=%d ticks=%d}",
                type, durationRounds, durationTicks);
    }
}
