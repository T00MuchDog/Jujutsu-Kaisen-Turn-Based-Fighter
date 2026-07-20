package com.jjktbf.model.character;

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
    private final AbilityConditionData activationCondition;
    private final boolean activationChanceEnabled;
    private final double activationChance;

    // Active-only
    private final String activeSubType;   // "QUEUED" | legacy "TRIGGERED" | null
    private final String activeMoveId;    // nullable
    private final String triggerCondition;// trigger condition name | null
    private final int    triggerThreshold;

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
        this.activationCondition = data.activationCondition == null
            ? AbilityConditionData.always() : data.activationCondition.copy();
        this.activationChanceEnabled = Boolean.TRUE.equals(data.activationChanceEnabled);
        this.activationChance = data.activationChance == null
            ? 1.0 : Math.max(0.0, Math.min(1.0, data.activationChance));
        this.activeSubType    = data.activeSubType;
        this.activeMoveId     = data.activeMoveId;
        this.triggerCondition = data.triggerCondition;
        this.triggerThreshold = data.triggerThreshold;
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
    public String getActiveSubType()    { return activeSubType; }
    public String getActiveMoveId()     { return activeMoveId; }
    public String getTriggerCondition() { return triggerCondition; }
    public int    getTriggerThreshold() { return triggerThreshold; }
    public AbilityConditionData getActivationCondition() { return activationCondition; }
    public boolean isActivationChanceEnabled() { return activationChanceEnabled; }
    public double getActivationChance() { return activationChanceEnabled ? activationChance : 1.0; }

    public boolean isPassive()  { return "PASSIVE".equalsIgnoreCase(category); }
    public boolean isActive()   { return "ACTIVE".equalsIgnoreCase(category); }
    public boolean isAlwaysActive() { return activationCondition.containsAlways(); }

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
