package com.jjktbf.model.character;

import java.util.Collections;
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

    // Active-only
    private final String activeSubType;   // "QUEUED" | "TRIGGERED" | null
    private final String activeMoveId;    // nullable
    private final String triggerCondition;// AbilityTrigger name | null
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
            ? Collections.unmodifiableList(data.effects) : List.of();
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

    public boolean isPassive()  { return "PASSIVE".equalsIgnoreCase(category); }
    public boolean isActive()   { return "ACTIVE".equalsIgnoreCase(category); }

    /** Total STAT_BONUS_POINTS this ability contributes (for character editor budget). */
    public int statBonusPoints() {
        return effects.stream()
            .filter(e -> AbilityEffectType.STAT_BONUS_POINTS.name().equals(e.type))
            .mapToInt(e -> e.intValue != null ? e.intValue : 0)
            .sum();
    }

    @Override
    public String toString() {
        return "Ability{" + id + " " + name + " [" + category + "]}";
    }
}
