package com.jjktbf.model.move;

/**
 * An instance of a status effect as it exists on a combatant mid-battle.
 * Immutable descriptor — the combat engine tracks duration countdown separately.
 */
public class StatusEffect {

    private final StatusEffectType type;

    /** How many rounds the effect lasts. -1 = until explicitly cleared. */
    private final int durationRounds;

    /** AP ticks after the configured rounds have elapsed. */
    private final int durationTicks;

    /** Magnitude of the effect (e.g. damage per tick, stat modifier amount). */
    private final double magnitude;

    public StatusEffect(StatusEffectType type, int durationRounds, double magnitude) {
        this(type, durationRounds, 0, magnitude);
    }

    public StatusEffect(
        StatusEffectType type,
        int durationRounds,
        int durationTicks,
        double magnitude
    ) {
        if (type == null) throw new IllegalArgumentException("Status effect type is required");
        validateDuration(durationRounds, durationTicks);
        if (!Double.isFinite(magnitude) || magnitude < 0) {
            throw new IllegalArgumentException("Status effect amount must be a non-negative number");
        }
        this.type            = type;
        this.durationRounds  = durationRounds;
        this.durationTicks   = durationTicks;
        this.magnitude       = magnitude;
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

    public StatusEffectType getType()     { return type; }
    public int getDurationRounds()        { return durationRounds; }
    public int getDurationTicks()         { return durationTicks; }
    public double getMagnitude()          { return magnitude; }

    @Override
    public String toString() {
        return String.format("StatusEffect{%s rounds=%d ticks=%d mag=%.2f}",
            type, durationRounds, durationTicks, magnitude);
    }
}
