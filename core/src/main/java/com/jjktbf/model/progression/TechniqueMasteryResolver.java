package com.jjktbf.model.progression;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.StatusEffect;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves authored per-field progressions into concrete battle-time values. */
public final class TechniqueMasteryResolver {

    private TechniqueMasteryResolver() {
    }

    public static int masteryOf(BattleCombatant combatant) {
        if (combatant == null || combatant.getEffectiveStats() == null) return 0;
        return Math.max(0, Math.min(300,
            combatant.getEffectiveStats().getCursedTechniqueMastery()));
    }

    public static AbilityEffectData resolve(AbilityEffectData source, int mastery) {
        if (source == null || source.masteryProgression == null
            || source.masteryProgression.isEmpty()) {
            return source;
        }
        AbilityEffectData resolved = source.copy();
        AbilityEffectType type = AbilityEffectType.fromName(source.type);
        Map<String, TechniqueMasteryProgressionData> values = source.masteryProgression;
        if (source.intValue != null) {
            resolved.intValue = TechniqueMasteryProgressions.resolve(
                values, TechniqueMasteryProgressions.INT_VALUE, source.intValue, mastery);
        }
        if (source.doubleValue != null) {
            TechniqueMasteryProgressionData progression = values.get(
                TechniqueMasteryProgressions.DOUBLE_VALUE);
            if (progression != null) {
                int authored = progression.resolve(mastery);
                resolved.doubleValue = type == AbilityEffectType.BATTLE_STAT_ADD
                    ? (double) authored : authored / 100.0;
            }
        }
        if (source.durationRounds != null) {
            resolved.durationRounds = TechniqueMasteryProgressions.resolve(
                values, TechniqueMasteryProgressions.DURATION_ROUNDS,
                source.durationRounds, mastery);
        }
        if (source.durationTicks != null) {
            resolved.durationTicks = TechniqueMasteryProgressions.resolve(
                values, TechniqueMasteryProgressions.DURATION_TICKS,
                source.durationTicks, mastery);
        }
        if (source.magnitude != null) {
            TechniqueMasteryProgressionData progression = values.get(
                TechniqueMasteryProgressions.MAGNITUDE);
            if (progression != null) resolved.magnitude = (double) progression.resolve(mastery);
        }
        if (source.uses != null) {
            resolved.uses = TechniqueMasteryProgressions.resolve(
                values, TechniqueMasteryProgressions.USES, source.uses, mastery);
        }
        resolved.codedParameters = resolveCodedParameters(
            source.codedParameters, values, mastery);
        return resolved;
    }

    public static AbilityConditionData resolve(AbilityConditionData source, int mastery) {
        if (source == null || source.masteryProgression == null
            || source.masteryProgression.isEmpty()) {
            return source;
        }
        AbilityConditionData resolved = source.copy();
        resolved.percentage = source.percentage == null ? null : resolvePercent(
            source.masteryProgression, TechniqueMasteryProgressions.PERCENTAGE,
            source.percentage, mastery);
        resolved.amount = source.amount == null ? null : resolveInt(
            source.masteryProgression, TechniqueMasteryProgressions.AMOUNT,
            source.amount, mastery);
        resolved.tick = source.tick == null ? null : resolveInt(
            source.masteryProgression, TechniqueMasteryProgressions.TICK,
            source.tick, mastery);
        resolved.round = source.round == null ? null : resolveInt(
            source.masteryProgression, TechniqueMasteryProgressions.ROUND,
            source.round, mastery);
        return resolved;
    }

    public static StatusEffect resolve(StatusEffect source, int mastery) {
        if (source == null || source.getMasteryProgression().isEmpty()) return source;
        Map<String, TechniqueMasteryProgressionData> values = source.getMasteryProgression();
        if (source.isCoded()) {
            Integer stackCount = source.getCodedStackCount();
            TechniqueMasteryProgressionData stackProgression = values.get(
                TechniqueMasteryProgressions.CODED_STACK_COUNT);
            if (stackProgression != null) stackCount = stackProgression.resolve(mastery);
            return StatusEffect.coded(
                source.getCodedAbilityKey(), source.getCodedAction(), source.getCodedTarget(),
                stackCount,
                resolveCodedParameters(source.getCodedParameters(), values, mastery),
                source.getMasteryProgression());
        }
        int rounds = TechniqueMasteryProgressions.resolve(
            values, TechniqueMasteryProgressions.DURATION_ROUNDS,
            source.getDurationRounds(), mastery);
        int ticks = TechniqueMasteryProgressions.resolve(
            values, TechniqueMasteryProgressions.DURATION_TICKS,
            source.getDurationTicks(), mastery);
        TechniqueMasteryProgressionData magnitudeProgression = values.get(
            TechniqueMasteryProgressions.MAGNITUDE);
        double magnitude = magnitudeProgression == null
            ? source.getMagnitude() : magnitudeProgression.resolve(mastery);
        return new StatusEffect(source.getType(), rounds, ticks, magnitude,
            source.getMasteryProgression());
    }

    public static int resolveInt(
        Map<String, TechniqueMasteryProgressionData> progressions,
        String field,
        Integer literal,
        int mastery
    ) {
        return TechniqueMasteryProgressions.resolve(
            progressions, field, literal == null ? 0 : literal, mastery);
    }

    public static double resolvePercent(
        Map<String, TechniqueMasteryProgressionData> progressions,
        String field,
        Double literal,
        int mastery
    ) {
        return TechniqueMasteryProgressions.resolvePercent(
            progressions, field, literal == null ? 0.0 : literal, mastery);
    }

    public static int codedParameter(
        Map<String, Integer> parameters,
        String key,
        int fallback
    ) {
        return parameters == null ? fallback : parameters.getOrDefault(key, fallback);
    }

    private static Map<String, Integer> resolveCodedParameters(
        Map<String, Integer> parameters,
        Map<String, TechniqueMasteryProgressionData> progressions,
        int mastery
    ) {
        if ((parameters == null || parameters.isEmpty())
            && (progressions == null || progressions.isEmpty())) {
            return parameters;
        }
        Map<String, Integer> resolved = parameters == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        if (progressions == null) return resolved;
        for (Map.Entry<String, TechniqueMasteryProgressionData> entry : progressions.entrySet()) {
            if (isGenericField(entry.getKey()) || entry.getValue() == null) continue;
            resolved.put(entry.getKey(), entry.getValue().resolve(mastery));
        }
        return resolved;
    }

    private static boolean isGenericField(String field) {
        return TechniqueMasteryProgressions.INT_VALUE.equals(field)
            || TechniqueMasteryProgressions.DOUBLE_VALUE.equals(field)
            || TechniqueMasteryProgressions.DURATION_ROUNDS.equals(field)
            || TechniqueMasteryProgressions.DURATION_TICKS.equals(field)
            || TechniqueMasteryProgressions.MAGNITUDE.equals(field)
            || TechniqueMasteryProgressions.USES.equals(field)
            || TechniqueMasteryProgressions.CODED_STACK_COUNT.equals(field);
    }
}
