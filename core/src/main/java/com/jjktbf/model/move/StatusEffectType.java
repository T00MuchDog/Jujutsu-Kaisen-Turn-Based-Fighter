package com.jjktbf.model.move;

import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.StatKey;

import java.util.Set;

/** Temporary flat increases and decreases that can be applied by moves or abilities. */
public enum StatusEffectType {

    // Base character stats
    VITALITY_INCREASE("Increase Vitality", StatKey.VITALITY, 1),
    VITALITY_DECREASE("Decrease Vitality", StatKey.VITALITY, -1),
    STRENGTH_INCREASE("Increase Strength", StatKey.STRENGTH, 1),
    STRENGTH_DECREASE("Decrease Strength", StatKey.STRENGTH, -1),
    DURABILITY_INCREASE("Increase Durability", StatKey.DURABILITY, 1),
    DURABILITY_DECREASE("Decrease Durability", StatKey.DURABILITY, -1),
    SPEED_INCREASE("Increase Speed", StatKey.SPEED, 1),
    SPEED_DECREASE("Decrease Speed", StatKey.SPEED, -1),
    COMBAT_ABILITY_INCREASE("Increase Combat Ability", StatKey.COMBAT_ABILITY, 1),
    COMBAT_ABILITY_DECREASE("Decrease Combat Ability", StatKey.COMBAT_ABILITY, -1),
    CURSED_ENERGY_RESERVES_INCREASE(
        "Increase Cursed Energy Reserves", StatKey.CURSED_ENERGY_RESERVES, 1),
    CURSED_ENERGY_RESERVES_DECREASE(
        "Decrease Cursed Energy Reserves", StatKey.CURSED_ENERGY_RESERVES, -1),
    CURSED_ENERGY_EFFICIENCY_INCREASE(
        "Increase Cursed Energy Efficiency", StatKey.CURSED_ENERGY_EFFICIENCY, 1),
    CURSED_ENERGY_EFFICIENCY_DECREASE(
        "Decrease Cursed Energy Efficiency", StatKey.CURSED_ENERGY_EFFICIENCY, -1),
    CURSED_ENERGY_OUTPUT_INCREASE(
        "Increase Cursed Energy Output", StatKey.CURSED_ENERGY_OUTPUT, 1),
    CURSED_ENERGY_OUTPUT_DECREASE(
        "Decrease Cursed Energy Output", StatKey.CURSED_ENERGY_OUTPUT, -1),
    JUJUTSU_SKILL_INCREASE("Increase Jujutsu Skill", StatKey.JUJUTSU_SKILL, 1),
    JUJUTSU_SKILL_DECREASE("Decrease Jujutsu Skill", StatKey.JUJUTSU_SKILL, -1),
    CURSED_TECHNIQUE_MASTERY_INCREASE(
        "Increase Cursed Technique Mastery", StatKey.CURSED_TECHNIQUE_MASTERY, 1),
    CURSED_TECHNIQUE_MASTERY_DECREASE(
        "Decrease Cursed Technique Mastery", StatKey.CURSED_TECHNIQUE_MASTERY, -1),

    // Derived combat stats that remain meaningful after a fight has started
    MAX_HP_INCREASE("Increase Max HP", BattleStatKey.MAX_HP, 1),
    MAX_HP_DECREASE("Decrease Max HP", BattleStatKey.MAX_HP, -1),
    MAX_CURSED_ENERGY_INCREASE("Increase Max Cursed Energy", BattleStatKey.MAX_CE, 1),
    MAX_CURSED_ENERGY_DECREASE("Decrease Max Cursed Energy", BattleStatKey.MAX_CE, -1),
    MAX_AP_INCREASE("Increase Max AP", BattleStatKey.MAX_AP, 1),
    MAX_AP_DECREASE("Decrease Max AP", BattleStatKey.MAX_AP, -1),
    ACCURACY_INCREASE("Increase Accuracy", BattleStatKey.ACCURACY, 1),
    ACCURACY_DECREASE("Decrease Accuracy", BattleStatKey.ACCURACY, -1),
    EVASION_INCREASE("Increase Evasion", BattleStatKey.EVASION, 1),
    EVASION_DECREASE("Decrease Evasion", BattleStatKey.EVASION, -1),
    POWER_INCREASE("Increase Power", BattleStatKey.POWER, 1),
    POWER_DECREASE("Decrease Power", BattleStatKey.POWER, -1),
    DEFENSE_INCREASE("Increase Defense", BattleStatKey.DEFENSE, 1),
    DEFENSE_DECREASE("Decrease Defense", BattleStatKey.DEFENSE, -1);

    private final String displayName;
    private final StatKey baseStat;
    private final BattleStatKey battleStat;
    private final int direction;

    StatusEffectType(String displayName, StatKey baseStat, int direction) {
        this(displayName, baseStat, null, direction);
    }

    StatusEffectType(String displayName, BattleStatKey battleStat, int direction) {
        this(displayName, null, battleStat, direction);
    }

    StatusEffectType(
        String displayName,
        StatKey baseStat,
        BattleStatKey battleStat,
        int direction
    ) {
        this.displayName = displayName;
        this.baseStat = baseStat;
        this.battleStat = battleStat;
        this.direction = direction;
    }

    public String displayName() {
        return displayName;
    }

    public StatKey baseStat() {
        return baseStat;
    }

    public BattleStatKey battleStat() {
        return battleStat;
    }

    public double signedMagnitude(double magnitude) {
        return direction * magnitude;
    }

    /** Resolve current names plus stat-based equivalents from pre-rework catalogs. */
    public static StatusEffectType fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Status effect type is required");
        }
        return switch (name.trim().toUpperCase()) {
            case "FOCUS" -> ACCURACY_INCREASE;
            case "CE_OUTPUT_UP" -> CURSED_ENERGY_OUTPUT_INCREASE;
            case "SPEED_UP" -> SPEED_INCREASE;
            case "POWER_UP" -> POWER_INCREASE;
            case "DEFENSE_UP" -> DEFENSE_INCREASE;
            case "BIND" -> EVASION_DECREASE;
            case "CURSED_SEAL" -> CURSED_ENERGY_EFFICIENCY_DECREASE;
            case "AP_DRAIN" -> MAX_AP_DECREASE;
            default -> valueOf(name.trim().toUpperCase());
        };
    }

    /** Resolve the direction encoded by old signed amounts into an explicit type. */
    public static StatusEffectType fromName(String name, double storedMagnitude) {
        StatusEffectType type = fromName(name);
        return storedMagnitude < 0 ? type.opposite() : type;
    }

    public StatusEffectType opposite() {
        String suffix = name().endsWith("_INCREASE") ? "_INCREASE" : "_DECREASE";
        String oppositeSuffix = "_INCREASE".equals(suffix) ? "_DECREASE" : "_INCREASE";
        return valueOf(name().substring(0, name().length() - suffix.length()) + oppositeSuffix);
    }

    /** Statuses selected by a persisted reference; old signed aliases can mean either direction. */
    public static Set<StatusEffectType> referencedTypes(String name) {
        try {
            StatusEffectType type = fromName(name);
            if (isSignedLegacyName(name)) return Set.of(type, type.opposite());
            return Set.of(type);
        } catch (IllegalArgumentException ignored) {
            return Set.of();
        }
    }

    public static String referenceDisplayName(String name) {
        Set<StatusEffectType> referenced = referencedTypes(name);
        if (referenced.isEmpty()) {
            return "Missing status: " + (name == null || name.isBlank() ? "(blank)" : name);
        }
        if (referenced.size() > 1) return "Legacy " + name + " (either direction)";
        return referenced.iterator().next().displayName();
    }

    private static boolean isSignedLegacyName(String name) {
        if (name == null) return false;
        return switch (name.trim().toUpperCase()) {
            case "FOCUS", "CE_OUTPUT_UP", "SPEED_UP", "POWER_UP", "DEFENSE_UP",
                 "BIND", "CURSED_SEAL", "AP_DRAIN" -> true;
            default -> false;
        };
    }

    /** Convert old fractional Power/Defense/Accuracy amounts into flat stat points. */
    public static double normalizeStoredMagnitude(String name, double magnitude) {
        if (name == null) return magnitude;
        double normalized = switch (name.trim().toUpperCase()) {
            case "FOCUS", "POWER_UP", "DEFENSE_UP" -> magnitude * 100.0;
            default -> magnitude;
        };
        return Math.abs(normalized);
    }
}
