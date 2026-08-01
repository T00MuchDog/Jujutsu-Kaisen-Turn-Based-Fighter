package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One activation predicate and the ability effects it controls. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbilityConditionRuleData {

    /** Recursive AND/OR predicate evaluated for this rule. */
    public AbilityConditionData condition;

    /** Null means every effect; otherwise only the listed stable effect IDs. */
    public List<String> targetEffectIds;

    /** Whether to roll {@link #activationChance} after the predicate matches. */
    public Boolean activationChanceEnabled;

    /** Activation probability as a fraction in [0, 1]. */
    public Double activationChance;

    /** When true, event leaves in an AND group must match the current trigger. */
    public Boolean matchSameTrigger;

    /** Optional progression for rule-level numeric fields such as activation chance. */
    public Map<String, TechniqueMasteryProgressionData> masteryProgression;

    public static AbilityConditionRuleData allEffects(AbilityConditionData condition) {
        AbilityConditionRuleData rule = new AbilityConditionRuleData();
        rule.condition = condition == null
            ? AbilityConditionData.manualActivation() : condition.copy();
        return rule;
    }

    public AbilityConditionRuleData copy() {
        AbilityConditionRuleData copy = new AbilityConditionRuleData();
        copy.condition = condition == null ? null : condition.copy();
        copy.targetEffectIds = targetEffectIds == null
            ? null : new ArrayList<>(targetEffectIds);
        copy.activationChanceEnabled = activationChanceEnabled;
        copy.activationChance = activationChance;
        copy.matchSameTrigger = matchSameTrigger;
        copy.masteryProgression = TechniqueMasteryProgressions.copy(masteryProgression);
        return copy;
    }

    @JsonIgnore
    public boolean targetsEffect(String effectId) {
        return targetEffectIds == null
            || (effectId != null && targetEffectIds.contains(effectId));
    }

    @JsonIgnore
    public double effectiveActivationChance() {
        if (!Boolean.TRUE.equals(activationChanceEnabled)) return 1.0;
        return activationChance == null
            ? 1.0 : Math.max(0.0, Math.min(1.0, activationChance));
    }

    /** Validate rule predicates and ensure every effect has exactly one owner. */
    public static String validationError(
        List<AbilityConditionRuleData> rules,
        List<AbilityEffectData> effects
    ) {
        if (rules == null || rules.isEmpty()) {
            return "An active ability needs at least one condition.";
        }
        List<AbilityEffectData> rows = effects == null ? List.of() : effects;
        String effectIdError = effectIdValidationError(rows);
        if (effectIdError != null) return effectIdError;
        Map<String, Integer> effectIndexes = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            AbilityEffectData effect = rows.get(i);
            effectIndexes.put(effect.effectId, i);
        }

        Map<String, Integer> assignments = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            AbilityConditionRuleData rule = rules.get(i);
            String prefix = "Condition " + (i + 1);
            if (rule == null) return prefix + " is missing.";
            if (rule.condition == null) return prefix + " predicate is missing.";
            String conditionError = AbilityConditionType.validationError(rule.condition);
            if (conditionError != null) return prefix + ": " + conditionError;
            if (Boolean.TRUE.equals(rule.activationChanceEnabled)
                && (rule.activationChance == null || !Double.isFinite(rule.activationChance)
                    || rule.activationChance < 0 || rule.activationChance > 1)) {
                return prefix + " activation chance must be between 0% and 100%.";
            }
            String progressionError = TechniqueMasteryProgressions.validationError(
                rule.masteryProgression,
                Boolean.TRUE.equals(rule.activationChanceEnabled)
                    ? Set.of(TechniqueMasteryProgressions.ACTIVATION_CHANCE) : Set.of());
            if (progressionError != null) return prefix + ": " + progressionError;
            if (rule.masteryProgression != null) {
                for (int mastery = 0; mastery <= CharacterStats.MAX_STAT; mastery++) {
                    double chance;
                    try {
                        chance = TechniqueMasteryProgressions.resolvePercent(
                            rule.masteryProgression,
                            TechniqueMasteryProgressions.ACTIVATION_CHANCE,
                            rule.effectiveActivationChance(), mastery);
                    } catch (RuntimeException exception) {
                        return prefix + " has invalid activation chance progression at CTM "
                            + mastery + ".";
                    }
                    if (chance < 0 || chance > 1) {
                        return prefix + " activation chance is outside 0%-100% at CTM "
                            + mastery + ".";
                    }
                }
            }

            Set<String> targets = rule.targetEffectIds == null
                ? effectIndexes.keySet() : new HashSet<>(rule.targetEffectIds);
            if (rule.targetEffectIds != null && targets.isEmpty()) {
                return prefix + " must target at least one effect or use All effects.";
            }
            for (String effectId : targets) {
                if (!effectIndexes.containsKey(effectId)) {
                    return prefix + " references an effect that no longer exists.";
                }
                assignments.merge(effectId, 1, Integer::sum);
            }
            boolean targetsGeneric = targets.stream()
                .map(effectIndexes::get)
                .map(rows::get)
                .anyMatch(effect -> !effect.isCoded());
            boolean targetsCoded = targets.stream()
                .map(effectIndexes::get)
                .map(rows::get)
                .anyMatch(AbilityEffectData::isCoded);
            if (targetsGeneric && targetsCoded) {
                return prefix
                    + " cannot mix coded and generic effects. Use a separate condition for each.";
            }
            if (targetsGeneric && containsPreResolutionHook(rule.condition)) {
                return prefix
                    + " uses a pre-resolution condition that can only target coded effects.";
            }
            if (targetsCoded && containsType(
                rule.condition, AbilityConditionType.MANUAL_ACTIVATION)) {
                return prefix + " manual activation can only target generic effects.";
            }
            if (targetsCoded) {
                for (String effectId : targets) {
                    AbilityEffectData effect = rows.get(effectIndexes.get(effectId));
                    String opportunityError = codedOpportunityError(
                        rule.condition, effect);
                    if (opportunityError != null) {
                        return prefix + ": " + opportunityError;
                    }
                }
            }
        }

        for (Map.Entry<String, Integer> entry : effectIndexes.entrySet()) {
            int effectNumber = entry.getValue() + 1;
            int assigned = assignments.getOrDefault(entry.getKey(), 0);
            if (assigned == 0) {
                return "Effect " + effectNumber + " is not linked to a condition.";
            }
            if (assigned > 1) {
                return "Effect " + effectNumber
                    + " is linked to more than one condition. Combine those predicates with OR.";
            }
        }
        return null;
    }

    /** Validate stable effect-row identities for active and passive abilities. */
    public static String effectIdValidationError(List<AbilityEffectData> effects) {
        if (effects == null || effects.isEmpty()) return "An ability needs at least one effect.";
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < effects.size(); i++) {
            AbilityEffectData effect = effects.get(i);
            if (effect == null || effect.effectId == null || effect.effectId.isBlank()) {
                return "Effect " + (i + 1) + " is missing a stable effect ID.";
            }
            if (!ids.add(effect.effectId)) {
                return "Effect " + (i + 1) + " uses a duplicate effect ID.";
            }
        }
        return null;
    }

    private static boolean containsPreResolutionHook(AbilityConditionData condition) {
        return containsType(condition, AbilityConditionType.ATTACK_CONNECTED)
            || containsType(condition, AbilityConditionType.CONNECTED_HIT_HAS_TAG)
            || containsType(condition, AbilityConditionType.FATAL_DAMAGE);
    }

    private static boolean containsType(
        AbilityConditionData condition,
        AbilityConditionType expected
    ) {
        if (condition == null) return false;
        if (expected.name().equalsIgnoreCase(condition.type)) return true;
        return condition.children != null && condition.children.stream()
            .anyMatch(child -> containsType(child, expected));
    }

    private static String codedOpportunityError(
        AbilityConditionData condition,
        AbilityEffectData effect
    ) {
        String key = normalized(effect.codedAbilityKey);
        String feature = normalized(effect.codedFeature);
        String label = key + "/" + feature;
        if (containsType(condition, AbilityConditionType.FATAL_DAMAGE)
            && !("MIRACLES".equals(key) && "FATEFUL_REPRIEVE".equals(feature))) {
            return "Fatal damage incoming is not a runtime opportunity for " + label + ".";
        }
        if ((containsType(condition, AbilityConditionType.ATTACK_CONNECTED)
            || containsType(condition, AbilityConditionType.CONNECTED_HIT_HAS_TAG))
            && !("RATIO".equals(key) && "REINFORCEMENT_RATIO".equals(feature))) {
            return "Connected-hit conditions are not runtime opportunities for " + label + ".";
        }
        return null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
