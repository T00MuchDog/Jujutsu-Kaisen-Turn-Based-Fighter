package com.jjktbf.model.character;

import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;

import java.util.List;

/**
 * Domain object representing a character ability.
 *
 * Immutable. Built from AbilityData at load time.
 *
 * Text layers:
 *   flavourText   — in-universe description shown to the player
 *   mechanicText  — precise mechanical description with ALL_CAPS keywords
 *                   (highlighted by the UI layer via regex on [A-Z_]{2,})
 */
public class Ability {

    private final String id;
    private final String name;
    private final String flavourText;
    private final String mechanicText;
    private final String category;        // "PASSIVE" | "ACTIVE"
    private final String sourceType;      // "CHARACTER" | "TECHNIQUE" | "MOVE" | "STAT_THRESHOLD"
    private final String sourceValue;     // nullable
    private final List<AbilityEffectData> effects;
    private final String codedAbilityKey;
    private final String codedFeature;
    private final AbilityConditionData activationCondition;
    private final boolean activationChanceEnabled;
    private final double activationChance;

    public Ability(AbilityData data) {
        this.id               = data.id;
        this.name             = data.name;
        this.flavourText      = data.flavourText  != null ? data.flavourText  : "";
        this.mechanicText     = data.mechanicText != null ? data.mechanicText : "";
        this.category         = data.category     != null ? data.category     : "PASSIVE";
        this.sourceType       = data.sourceType   != null ? data.sourceType   : "CHARACTER";
        this.sourceValue      = data.sourceValue;
        this.effects          = data.effects != null
            ? data.effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityEffectData::copy)
                .toList()
            : List.of();
        for (AbilityEffectData effect : effects) {
            AbilityEffectType type;
            try { type = AbilityEffectType.fromName(effect.type); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!type.uses(AbilityEffectParameter.DURATION)) continue;
            int rounds = effect.durationRounds == null ? -1 : effect.durationRounds;
            int ticks = effect.durationTicks == null ? 0 : effect.durationTicks;
            if (type.uses(AbilityEffectParameter.STATUS_TYPE)) {
                StatusEffectType status;
                try {
                    status = StatusEffectType.fromName(effect.stringValue);
                } catch (IllegalArgumentException ignored) {
                    StatusEffect.validateDuration(rounds, ticks);
                    continue;
                }
                StatusEffect.validateDuration(status, rounds, ticks);
            } else {
                StatusEffect.validateDuration(rounds, ticks);
            }
        }
        this.codedAbilityKey  = data.codedAbilityKey;
        this.codedFeature     = data.codedFeature;
        this.activationCondition = isActive()
            ? (data.activationCondition == null
                ? AbilityConditionData.manualActivation() : data.activationCondition.copy())
            : null;
        this.activationChanceEnabled = isActive()
            && Boolean.TRUE.equals(data.activationChanceEnabled);
        this.activationChance = data.activationChance == null
            ? 1.0 : Math.max(0.0, Math.min(1.0, data.activationChance));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getId()               { return id; }
    public String getName()             { return name; }
    public String getFlavourText()      { return flavourText; }
    public String getMechanicText()     { return mechanicText; }
    public String getCategory()         { return category; }
    public String getSourceType()       { return sourceType; }
    public String getSourceValue()      { return sourceValue; }
    public List<AbilityEffectData> getEffects() { return effects; }
    public String getCodedAbilityKey()  { return codedAbilityKey; }
    public String getCodedFeature()     { return codedFeature; }
    public AbilityConditionData getActivationCondition() { return activationCondition; }
    public boolean isActivationChanceEnabled() { return activationChanceEnabled; }
    public double getActivationChance() { return activationChanceEnabled ? activationChance : 1.0; }

    public boolean isPassive()  { return "PASSIVE".equalsIgnoreCase(category); }
    public boolean isActive()   { return "ACTIVE".equalsIgnoreCase(category); }
    public boolean isCoded()    { return codedAbilityKey != null && !codedAbilityKey.isBlank(); }

    /** Total STAT_BONUS_POINTS this ability contributes (for character editor budget). */
    public int statBonusPoints() {
        return effects.stream()
            .filter(e -> AbilityEffectType.STAT_BONUS_POINTS.name().equalsIgnoreCase(e.type))
            .mapToInt(e -> e.intValue != null ? e.intValue : 0)
            .sum();
    }

    @Override
    public String toString() {
        return "Ability{" + id + " " + name + " [" + category + "]}";
    }
}
