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
    private final String sourceType;      // CHARACTER | TECHNIQUE | MOVE | STAT_THRESHOLD | ABILITY | SHIKIGAMI
    private final String sourceValue;     // nullable
    private final List<AbilityEffectData> effects;
    private final List<AbilityConditionRuleData> activationConditions;

    public Ability(AbilityData data) {
        this.id               = data.id;
        this.name             = data.name;
        this.flavourText      = data.flavourText  != null ? data.flavourText  : "";
        this.mechanicText     = data.mechanicText != null ? data.mechanicText : "";
        this.category         = data.category     != null ? data.category     : "PASSIVE";
        this.sourceType       = data.sourceType   != null ? data.sourceType   : "CHARACTER";
        this.sourceValue      = data.sourceValue;
        List<AbilityEffectData> copiedEffects = data.effects != null
            ? new java.util.ArrayList<>(data.effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityEffectData::copy)
                .toList())
            : new java.util.ArrayList<>();
        AbilityData.ensureEffectIds(copiedEffects);
        this.effects = List.copyOf(copiedEffects);
        boolean techniqueSource = "TECHNIQUE".equalsIgnoreCase(sourceType);
        for (AbilityEffectData effect : effects) {
            AbilityEffectType type;
            try { type = AbilityEffectType.fromName(effect.type); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!techniqueSource && effect.masteryProgression != null
                && !effect.masteryProgression.isEmpty()) {
                throw new IllegalArgumentException(
                    "Only TECHNIQUE abilities may use mastery progression.");
            }
            if (isPassive()
                && StatKey.CURSED_TECHNIQUE_MASTERY.fieldName.equalsIgnoreCase(effect.stat)
                && effect.masteryProgression != null && !effect.masteryProgression.isEmpty()) {
                throw new IllegalArgumentException(
                    "A passive CTM-changing effect cannot derive its value from CTM.");
            }
            String effectError = type.validationError(effect);
            if (effectError != null) {
                throw new IllegalArgumentException(
                    "Invalid effect in ability '" + name + "': " + effectError);
            }
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
        this.activationConditions = isActive()
            ? List.copyOf(data.resolvedActivationConditions()) : List.of();
        if (!techniqueSource && activationConditions.stream().anyMatch(
            Ability::hasMasteryProgression)) {
            throw new IllegalArgumentException(
                "Only TECHNIQUE abilities may use condition mastery progression.");
        }
    }

    private static boolean hasMasteryProgression(AbilityConditionRuleData rule) {
        if (rule == null) return false;
        if (rule.masteryProgression != null && !rule.masteryProgression.isEmpty()) return true;
        return hasMasteryProgression(rule.condition);
    }

    private static boolean hasMasteryProgression(AbilityConditionData condition) {
        if (condition == null) return false;
        if (condition.masteryProgression != null && !condition.masteryProgression.isEmpty()) {
            return true;
        }
        return condition.children != null
            && condition.children.stream().anyMatch(Ability::hasMasteryProgression);
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
    public List<AbilityConditionRuleData> getActivationConditions() { return activationConditions; }

    /** Legacy convenience accessor for callers that only support one rule. */
    public AbilityConditionData getActivationCondition() {
        return activationConditions.isEmpty() ? null : activationConditions.get(0).condition;
    }

    public boolean isActivationChanceEnabled() {
        return !activationConditions.isEmpty()
            && Boolean.TRUE.equals(activationConditions.get(0).activationChanceEnabled);
    }

    public double getActivationChance() {
        return activationConditions.isEmpty()
            ? 1.0 : activationConditions.get(0).effectiveActivationChance();
    }

    public boolean isPassive()  { return "PASSIVE".equalsIgnoreCase(category); }
    public boolean isActive()   { return "ACTIVE".equalsIgnoreCase(category); }

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
