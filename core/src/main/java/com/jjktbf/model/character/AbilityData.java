package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * DTO for one ability definition, stored in data/abilities/all_abilities.json.
 *
 * An ability has two layers of text:
 *
 *   flavourText   — immersive, in-universe description. Shown to the player
 *                   during gameplay (on the character sheet, ability inspect screen).
 *                   Written as if describing the ability from within the JJK world.
 *                   Example: "A rare ocular Jujutsu passed down through the Gojo clan..."
 *
 *   mechanicText  — plain mechanical description for the designer / player who wants
 *                   to understand exactly what the ability does.
 *                   Contains KEYWORDS in ALL_CAPS that the editor and UI can highlight.
 *                   Example: "Sets CURSED_ENERGY_EFFICIENCY to MAX. Reduces all CE costs
 *                   to their MINIMUM. Grants +20 ACCURACY on all moves."
 *
 * Keywords are not stored separately — they are words in ALL_CAPS within mechanicText.
 * The UI layer extracts and highlights them via regex at render time.
 *
 * Source types:
 *   CHARACTER      — available to every character (no prerequisite)
 *   TECHNIQUE      — appears as an activatable node in a named technique's tree
 *   MOVE           — requires knowing a specific move (by ID)
 *   STAT_THRESHOLD — requires a stat to be at or above a threshold ("strength>=200")
 *   ABILITY        — available while another specific ability is assigned (by ID or name)
 *                    e.g. "Precog" is available after assigning "Heavenly Restriction"
 *   SHIKIGAMI      — available to every SHIKIGAMI character definition
 *   CURSED_TOOL    — active automatically while one specific cursed tool is equipped
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbilityData {

    public String id;          // 6-digit auto-assigned
    public String name;

    /** In-universe flavour text. Shown to the player. */
    public String flavourText;

    /**
     * Mechanical description with ALL_CAPS keywords.
     * Keywords are highlighted in the UI for quick scanning.
     * Example: "Sets CURSED_ENERGY_EFFICIENCY to MAX. All CE costs reduced to MINIMUM."
     */
    public String mechanicText;

    /** "PASSIVE" or "ACTIVE" */
    public String category;

    /** Source type such as CHARACTER, TECHNIQUE, MOVE, ABILITY, or CURSED_TOOL. */
    public String sourceType;

    /**
     * Source qualifier:
     *   TECHNIQUE      → technique name (e.g. "Limitless")
     *   MOVE           → move ID (e.g. "000005")
     *   STAT_THRESHOLD → "stat>=value" (e.g. "cursedTechniqueMastery>=200")
     *   ABILITY        → ability ID or ability name (e.g. "000003" or "Heavenly Restriction")
     *   CURSED_TOOL    → cursed-tool ID (e.g. "000000")
     *   CHARACTER      → null
     */
    public String sourceValue;

    /** Ordered activation rules for active abilities. */
    public List<AbilityConditionRuleData> activationConditions;

    /** Legacy single-rule predicate. New content uses {@link #activationConditions}. */
    public AbilityConditionData activationCondition;

    /** Legacy single-rule chance flag. */
    public Boolean activationChanceEnabled;

    /** Legacy single-rule activation probability. */
    public Double activationChance;

    /** The list of effect primitives this ability applies. */
    public List<AbilityEffectData> effects;

    /**
     * Legacy technique mastery threshold. New technique progression is authored
     * in {@code InnateTechniqueData.skillTree}; synchronization migrates a
     * positive legacy value into a mastery prerequisite when adding the node.
     */
    public int masteryThreshold;

    // ── Derived helpers ───────────────────────────────────────────────────────

    @JsonIgnore public boolean isPassive()  { return "PASSIVE".equalsIgnoreCase(category); }
    @JsonIgnore public boolean isActive()   { return "ACTIVE".equalsIgnoreCase(category); }

    @JsonIgnore public double effectiveActivationChance() {
        if (!isActive() || !Boolean.TRUE.equals(activationChanceEnabled)) return 1.0;
        return activationChance == null ? 1.0 : Math.max(0.0, Math.min(1.0, activationChance));
    }

    /** Return copied activation rules, adapting legacy single-condition content. */
    @JsonIgnore
    public List<AbilityConditionRuleData> resolvedActivationConditions() {
        if (!isActive()) return List.of();
        if (activationConditions != null) {
            return activationConditions.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityConditionRuleData::copy)
                .toList();
        }
        List<AbilityConditionRuleData> migrated = legacyActivationConditions();
        if (migrated != null) return migrated;
        AbilityConditionRuleData fallback = AbilityConditionRuleData.allEffects(
            activationCondition == null
                ? AbilityConditionData.manualActivation() : activationCondition);
        fallback.activationChanceEnabled = activationChanceEnabled;
        fallback.activationChance = activationChance;
        return List.of(fallback);
    }

    /** Convert legacy activation fields and assign durable IDs to effect rows. */
    @JsonIgnore
    public boolean migrateActivationData() {
        boolean changed = ensureEffectIds(effects);
        if (isActive() && activationConditions == null) {
            List<AbilityConditionRuleData> migrated = legacyActivationConditions();
            if (migrated != null
                && AbilityConditionRuleData.validationError(migrated, effects) == null) {
                activationConditions = new ArrayList<>(migrated);
                changed = true;
            }
        }
        if (!isActive() || activationConditions != null) {
            changed |= activationCondition != null
                || activationChanceEnabled != null || activationChance != null;
            activationCondition = null;
            activationChanceEnabled = null;
            activationChance = null;
        }
        return changed;
    }

    /** Assign deterministic IDs only to rows that do not already have one. */
    public static boolean ensureEffectIds(List<? extends AbilityEffectData> effects) {
        if (effects == null) return false;
        boolean changed = false;
        Set<String> used = new HashSet<>();
        for (AbilityEffectData effect : effects) {
            if (effect != null && effect.effectId != null && !effect.effectId.isBlank()) {
                used.add(effect.effectId);
            }
        }
        int next = 0;
        for (AbilityEffectData effect : effects) {
            if (effect == null || (effect.effectId != null && !effect.effectId.isBlank())) continue;
            String candidate;
            do {
                candidate = String.format("effect-%06d", next++);
            } while (used.contains(candidate));
            effect.effectId = candidate;
            used.add(candidate);
            changed = true;
        }
        return changed;
    }

    private List<AbilityConditionRuleData> legacyActivationConditions() {
        List<AbilityEffectData> normalizedEffects = effects == null
            ? new ArrayList<>()
            : effects.stream()
                .filter(java.util.Objects::nonNull)
                .map(AbilityEffectData::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ensureEffectIds(normalizedEffects);
        List<AbilityConditionRuleData> codedRules = new ArrayList<>();
        List<String> genericEffectIds = new ArrayList<>();
        for (AbilityEffectData effect : normalizedEffects) {
            if (!effect.isCoded()) {
                genericEffectIds.add(effect.effectId);
                continue;
            }
            AbilityConditionRuleData rule = legacyCodedRule(effect);
            if (rule == null) return null;
            codedRules.add(rule);
        }
        if (codedRules.isEmpty()) {
            AbilityConditionRuleData legacy = AbilityConditionRuleData.allEffects(
                activationCondition == null
                    ? AbilityConditionData.manualActivation() : activationCondition);
            legacy.activationChanceEnabled = activationChanceEnabled;
            legacy.activationChance = activationChance;
            return List.of(legacy);
        }
        List<AbilityConditionRuleData> rules = new ArrayList<>();
        if (!genericEffectIds.isEmpty()) {
            AbilityConditionRuleData generic = AbilityConditionRuleData.allEffects(
                activationCondition == null
                    ? AbilityConditionData.manualActivation() : activationCondition);
            generic.targetEffectIds = new ArrayList<>(genericEffectIds);
            generic.activationChanceEnabled = activationChanceEnabled;
            generic.activationChance = activationChance;
            rules.add(generic);
        }
        rules.addAll(codedRules);
        return rules;
    }

    private static AbilityConditionRuleData legacyCodedRule(AbilityEffectData effect) {
        if (effect == null || !effect.isCoded()) return null;
        String key = normalized(effect.codedAbilityKey);
        String feature = normalized(effect.codedFeature);
        AbilityConditionData condition;
        if ("MIRACLES".equals(key) && "RESERVOIR".equals(feature)) {
            condition = AbilityConditionType.BATTLE_STARTED.createDefault();
        } else if ("MIRACLES".equals(key) && "FATEFUL_REPRIEVE".equals(feature)) {
            AbilityConditionData fatal = AbilityConditionType.FATAL_DAMAGE.createDefault();
            AbilityConditionData hasMiracle =
                AbilityConditionType.CODED_STATE_AT_OR_ABOVE.createDefault();
            hasMiracle.codedAbilityKey = "MIRACLES";
            hasMiracle.amount = 1;
            condition = AbilityConditionData.all(List.of(fatal, hasMiracle));
        } else if ("MIRACLES".equals(key) && "FORTUNE_RECLAIMED".equals(feature)) {
            AbilityConditionData belowCap =
                AbilityConditionType.CODED_STATE_AT_OR_BELOW.createDefault();
            belowCap.codedAbilityKey = "MIRACLES";
            belowCap.amount = 5;
            AbilityConditionData missed = AbilityConditionType.ATTACK_MISSED.createDefault();
            missed.actor = AbilityConditionActor.ENEMY.name();
            AbilityConditionData eventTarget = AbilityConditionType.EVENT_TARGET.createDefault();
            AbilityConditionData blackFlash = AbilityConditionType.BLACK_FLASH_HIT.createDefault();
            condition = AbilityConditionData.all(List.of(
                belowCap, AbilityConditionData.any(List.of(
                    AbilityConditionData.all(List.of(missed, eventTarget)), blackFlash))));
        } else if ("RATIO".equals(key) && "REINFORCEMENT_RATIO".equals(feature)) {
            AbilityConditionData connected = AbilityConditionType.ATTACK_CONNECTED.createDefault();
            AbilityConditionData physical =
                AbilityConditionType.CONNECTED_HIT_HAS_TAG.createDefault();
            physical.moveTag = "PHYSICAL";
            AbilityConditionData cursedEnergy =
                AbilityConditionType.CONNECTED_HIT_HAS_TAG.createDefault();
            cursedEnergy.moveTag = "CURSED_ENERGY";
            condition = AbilityConditionData.all(List.of(
                connected, physical, cursedEnergy));
        } else if ("NEW_SHADOW_STYLE".equals(key)
            && "SIMPLE_DOMAIN_BINDING_VOW".equals(feature)) {
            condition = AbilityConditionData.always();
        } else {
            return null;
        }

        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(condition);
        rule.targetEffectIds = new ArrayList<>(List.of(effect.effectId));
        if (!condition.containsAlways()) rule.matchSameTrigger = true;
        if ("RATIO".equals(key)) {
            rule.activationChanceEnabled = true;
            rule.activationChance = 0.05;
        }
        return rule;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Compute how many STAT_BONUS_POINTS this ability grants.
     * Used by the character editor to adjust the point-buy budget.
     */
    public int statBonusPoints() {
        if (effects == null) return 0;
        return effects.stream()
            .filter(e -> e != null
                && AbilityEffectType.STAT_BONUS_POINTS.name().equalsIgnoreCase(e.type))
            .mapToInt(e -> e.intValue != null ? e.intValue : 0)
            .sum();
    }

    /** One-line summary for list views. */
    public String summaryLine() {
        String cat = (category != null ? category : "?");
        String src = sourceType != null ? sourceType : "";
        String srcVal = sourceValue != null ? " (" + sourceValue + ")" : "";
        return String.format("[%s] [%s%s]", cat, src, srcVal);
    }
}
