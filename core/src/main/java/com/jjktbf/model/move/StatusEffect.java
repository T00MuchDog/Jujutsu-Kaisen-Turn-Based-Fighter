package com.jjktbf.model.move;

/**
 * An instance of a status effect as it exists on a combatant mid-battle.
 * Immutable descriptor — the combat engine tracks duration countdown separately.
 */
public class StatusEffect {

    private final StatusEffectType type;

    /** How many rounds the effect lasts. -1 = until explicitly cleared. */
    private final int durationRounds;

    /** Magnitude of the effect (e.g. damage per tick, stat modifier amount). */
    private final double magnitude;

    public StatusEffect(StatusEffectType type, int durationRounds, double magnitude) {
        this.type            = type;
        this.durationRounds  = durationRounds;
        this.magnitude       = magnitude;
    }

    public StatusEffectType getType()     { return type; }
    public int getDurationRounds()        { return durationRounds; }
    public double getMagnitude()          { return magnitude; }

    @Override
    public String toString() {
        return String.format("StatusEffect{%s dur=%d mag=%.2f}", type, durationRounds, magnitude);
    }
}
