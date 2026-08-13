package com.jjktbf.model.progression;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared field keys and helpers for per-value cursed-technique-mastery progression. */
public final class TechniqueMasteryProgressions {

    public static final String INT_VALUE = "intValue";
    public static final String DOUBLE_VALUE = "doubleValue";
    public static final String DURATION_ROUNDS = "durationRounds";
    public static final String DURATION_TICKS = "durationTicks";
    public static final String MAGNITUDE = "magnitude";
    public static final String PER_TICK_REMOVAL_CHANCE = "perTickRemovalChance";
    public static final String USES = "uses";
    public static final String CODED_STACK_COUNT = "codedStackCount";
    public static final String ACTIVATION_CHANCE = "activationChance";
    public static final String PERCENTAGE = "percentage";
    public static final String AMOUNT = "amount";
    public static final String TICK = "tick";
    public static final String ROUND = "round";

    private TechniqueMasteryProgressions() {
    }

    public static Map<String, TechniqueMasteryProgressionData> copy(
        Map<String, TechniqueMasteryProgressionData> source
    ) {
        if (source == null) return null;
        Map<String, TechniqueMasteryProgressionData> copy = new LinkedHashMap<>();
        for (Map.Entry<String, TechniqueMasteryProgressionData> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().copy());
        }
        return copy;
    }

    public static Map<String, Integer> copyIntegers(Map<String, Integer> source) {
        return source == null ? null : new LinkedHashMap<>(source);
    }

    public static int resolve(
        Map<String, TechniqueMasteryProgressionData> progressions,
        String field,
        int literal,
        int mastery
    ) {
        TechniqueMasteryProgressionData progression = progression(progressions, field);
        return progression == null ? literal : progression.resolve(mastery);
    }

    /** Resolve an integer-authored percentage and convert it to the runtime fraction. */
    public static double resolvePercent(
        Map<String, TechniqueMasteryProgressionData> progressions,
        String field,
        double literalFraction,
        int mastery
    ) {
        TechniqueMasteryProgressionData progression = progression(progressions, field);
        return progression == null ? literalFraction : progression.resolve(mastery) / 100.0;
    }

    public static TechniqueMasteryProgressionData progression(
        Map<String, TechniqueMasteryProgressionData> progressions,
        String field
    ) {
        return progressions == null || field == null ? null : progressions.get(field);
    }

    public static String validationError(
        Map<String, TechniqueMasteryProgressionData> progressions,
        Set<String> allowedFields
    ) {
        if (progressions == null || progressions.isEmpty()) return null;
        for (Map.Entry<String, TechniqueMasteryProgressionData> entry : progressions.entrySet()) {
            if (entry.getKey() == null || !allowedFields.contains(entry.getKey())) {
                return "Unsupported mastery progression field: " + entry.getKey();
            }
            if (entry.getValue() == null) {
                return "Mastery progression for " + entry.getKey() + " is missing.";
            }
            String error = entry.getValue().validationError();
            if (error != null) return entry.getKey() + ": " + error;
        }
        return null;
    }
}
