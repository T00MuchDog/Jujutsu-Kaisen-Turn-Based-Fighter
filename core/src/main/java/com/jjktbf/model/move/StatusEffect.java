package com.jjktbf.model.move;

/**
 * An instance of a status effect as it exists on a combatant mid-battle.
 * Immutable descriptor — the combat engine tracks duration countdown separately.
 *
 * <p>An effect row is exactly one of two flavours:
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
 * </ul>
 * The two flavours are mutually exclusive: a coded action leaves {@code type} null,
 * and a status effect leaves the coded fields blank.
 */
public class StatusEffect {

    private final StatusEffectType type;

    /** How many rounds the effect lasts. -1 = until explicitly cleared. */
    private final int durationRounds;

    /** AP ticks after the configured rounds have elapsed. */
    private final int durationTicks;

    /** Magnitude of a stat-modifying effect. Non-stat statuses store zero. */
    private final double magnitude;

    /** Coded-ability key for an effect that cannot be expressed as a status. Blank for status effects. */
    private final String codedAbilityKey;

    /** Action interpreted by {@link #codedAbilityKey} when this effect fires. Blank for status effects. */
    private final String codedAction;

    /** Coded action's configurable target/mode. Blank when the action has no target setting. */
    private final String codedTarget;

    /** Number of Ratio stacks created by a configured coded action. */
    private final Integer codedStackCount;

    public StatusEffect(StatusEffectType type, int durationRounds, double magnitude) {
        this(type, durationRounds, 0, magnitude);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude
    ) {
        this(type, durationRounds, durationTicks, magnitude, null, null, null, null);
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
            codedAbilityKey, codedAction, null, null);
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
        boolean coded = codedAbilityKey != null && !codedAbilityKey.isBlank();
        if (type == null && !coded) {
            throw new IllegalArgumentException("Status effect type is required for a non-coded effect");
        }
        if (!coded) {
            validateDuration(type, durationRounds, durationTicks);
            if (!Double.isFinite(magnitude) || magnitude < 0) {
                throw new IllegalArgumentException("Status effect amount must be a non-negative number");
            }
        }
        this.type            = type;
        this.durationRounds  = durationRounds;
        this.durationTicks   = durationTicks;
        this.magnitude       = !coded && !type.usesMagnitude() ? 0.0 : magnitude;
        this.codedAbilityKey = codedAbilityKey;
        this.codedAction     = codedAction;
        this.codedTarget     = codedTarget;
        this.codedStackCount = codedStackCount;
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
    }

    public StatusEffectType getType()     { return type; }
    public int getDurationRounds()        { return durationRounds; }
    public int getDurationTicks()         { return durationTicks; }
    public double getMagnitude()          { return magnitude; }
    public String getCodedAbilityKey()    { return codedAbilityKey; }
    public String getCodedAction()        { return codedAction; }
    public String getCodedTarget()        { return codedTarget; }
    public Integer getCodedStackCount()   { return codedStackCount; }

    /** True when this row carries a coded action (and is therefore not a status effect). */
    public boolean isCoded() {
        return codedAbilityKey != null && !codedAbilityKey.isBlank();
    }

    @Override
    public String toString() {
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
