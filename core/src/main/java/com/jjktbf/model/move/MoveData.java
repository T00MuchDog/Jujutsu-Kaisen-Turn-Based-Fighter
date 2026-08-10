package com.jjktbf.model.move;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain data object (DTO) for serialising/deserialising a Move to/from JSON.
 *
 * ID: 6-digit zero-padded integer string, auto-assigned by MoveRepository.
 *
 * Category is derived at runtime from the tags list — it is not stored separately.
 * Tags is the canonical representation; MoveCategory is computed via MoveCategory.fromTags().
 *
 * requiredTechniqueId stores the technique's ID string (e.g. "SHRINE").
 * Technique IDs live in TechniqueRepository and are resolved separately.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveData {

    public String id;           // 6-digit auto-assigned, e.g. "000003"
    public String name;
    public String description;

    /**
     * List of MoveTag enum names applied to this move.
     * e.g. ["PHYSICAL", "ATTACK"]  or  ["INNATE_TECHNIQUE", "ATTACK", "CURSED_ENERGY"]
     * The MoveCategory is derived from this set at conversion time.
     */
    public List<String> tags;

    public int     basePower;
    /** Null in legacy data; when present, this ordered list is authoritative. */
    public List<HitComponentData> hitComponents;
    public double  baseAccuracy   = 1.0;
    public boolean neverMiss      = false;

    /**
     * Backs the STUN move tag. When true, a successful hit stuns the defender's
     * segment(s) on the current tick. Not part of {@link MoveCategory}; stored as a
     * dedicated flag so it survives the DTO↔domain round-trip.
     */
    public boolean stun           = false;

    /**
     * Backs the GUARD_BREAK move tag. When true, a successful hit ignores the
     * defender's blocking defensive moves (BLOCK).
     */
    public boolean guardBreak     = false;

    /**
     * Backs the HEAVY move tag. When true, an action segment carrying this move
     * cannot be stunned by a STUN-tagged hit.
     */
    public boolean heavy          = false;

    /**
     * Move potency tier (1–5). For attack and defensive moves, gates which
     * defences stop which attacks (a defence applies iff its potency ≥ the
     * attack's). Utility moves ignore it. Defaults to 1.
     */
    public int     potency        = 1;

    /**
     * If true, this move requires the wielder to have a weapon
     * (CharacterData.hasWeapon). Forced on for PARRY defence moves.
     */
    public boolean weaponRequired = false;

    public int     apCost;
    public int     unleashPoint;

    public int     baseCeCost     = 0;
    /** Null in older saves; then inferred from a positive base CE cost on load. */
    public Boolean hasCeCost;
    public int     minCeCost      = 0;
    public int     maxCeCost      = 0;

    /** DefenseType enum name */
    public String  defenseType    = "NONE";

    /** BlockStyle enum name (PERCENTAGE / FLAT). Used only when defenseType = BLOCK. */
    public String  blockStyle     = "PERCENTAGE";

    /** Block/dodge/parry shared field: duration in AP ticks. 0 = use move's apCost. -1 = end of round. */
    public int     blockDuration = 0;
    /** Tags this block or parry affects. Null = all damage types. */
    public List<String> blockAffectedTags;
    /** PERCENTAGE block only: percentage of damage reduced (0-100). 100 = full block. */
    public int     blockDamageReduction = 100;
    /** FLAT block only: flat damage amount subtracted from incoming attacks. */
    public int     blockFlatReduction = 0;

    /** DODGE only: chance (0-100%) to avoid a matching incoming attack. */
    public int     dodgeChance    = 0;
    /** DODGE only: MELEE / RANGED / BOTH. */
    public String  dodgeScope     = "BOTH";

    /** PARRY only: AP ticks to stagger the attacker on a successful non-GUARD_BREAK parry. 0 = none. */
    public int     parryStaggerTicks = 0;

    /** List of on-hit StatusEffect descriptors */
    public List<StatusEffectData> onHitEffects;

    /** List of self StatusEffect descriptors */
    public List<StatusEffectData> selfEffects;

    /** Status effects applied to the defender when a BLOCK negates/reduces a hit. */
    public List<StatusEffectData> onBlockEffects;
    /** Status effects applied to the defender when a PARRY negates a hit. */
    public List<StatusEffectData> onParryEffects;
    /** Status effects applied to the defender when a DODGE avoids a hit. */
    public List<StatusEffectData> onDodgeEffects;

    /** Prerequisite stats: {"strength": 80, "speed": 60, ...} */
    public Map<String, Integer> prerequisites;

    /**
     * Human-readable technique name this move requires (e.g. "Shrine", "Blood Manipulation").
     * Null means no technique restriction.
     * The numeric technique ID is resolved via TechniqueRepository at load time.
     */
    public String  requiredTechniqueId;

    public boolean isFreeMove = false;

    /** Maximum placements per round. 0 means unlimited. */
    public int moveCap = 0;

    /**
     * Canonical shikigami character id summoned when this move reaches its unleash
     * point. Null/blank for non-summoning moves. Only valid SHIKIGAMI definitions
     * may be referenced (validated in editors and the authoritative catalog).
     */
    public String summonCharacterId;

    /**
     * {@link AoeType} enum name for an AOE move's authoritative targeting shape.
     * Blank/null on a non-AOE move; also blank on legacy AOE saves, which are
     * migrated to a default at load time (ALL_ENEMIES, or ALL_OTHERS when the
     * FRIENDLY_FIRE tag is present).
     */
    public String aoeType;

    /**
     * Target count for {@link AoeType#MULTIPLE} AOE moves. Ignored for the other
     * shapes. Defaults to 2; must be ≥ 2 when used.
     */
    public int aoeTargetCount = 2;

    /** Cannot be assigned directly; an ability must add this move to the character. */
    public boolean mustBeGranted = false;

    /**
     * Editor-only grouping flag. True places this record under Shikigami in the
     * Move Editor; null or false places it under Sorcerer. It has no runtime effect.
     */
    public Boolean shikigamiMove;

    // -------------------------------------------------------------------------
    // Hit component sub-DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HitComponentData {
        public int basePower;
        /** Damage-type MoveTag names only; range and modifiers stay on the move. */
        public List<String> tags;
        /** Nonnegative offset from the parent move's unleash/fire tick. */
        public int delayTicks = 0;
        public boolean requiresPreviousConnection = false;
        public boolean avoidable = true;
        /**
         * Per-hit base accuracy as a fraction [0.0, 1.0]. A value &lt;= 0 in legacy
         * data means "inherit the parent move's baseAccuracy" — preserved so old
         * saves round-trip without silently changing every component to 0%.
         */
        public double baseAccuracy = -1.0;
        /** On-hit status effects applied when this specific component connects. */
        public List<StatusEffectData> onHitEffects;

        public HitComponent toHitComponent() {
            return toHitComponent(null);
        }

        private HitComponent toHitComponent(List<StatusEffectData> inheritedOnHitEffects) {
            EnumSet<MoveTag> parsed = EnumSet.noneOf(MoveTag.class);
            if (tags != null) {
                for (String tag : tags) {
                    if (tag == null || tag.isBlank()) continue;
                    MoveTag parsedTag;
                    try {
                        parsedTag = MoveTag.valueOf(tag.trim().toUpperCase());
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(
                            "Unknown hit-component tag: " + tag, exception);
                    }
                    parsed.add(parsedTag);
                }
            }
            // baseAccuracy <= 0 (the legacy "unset" marker) → inherit the move's.
            double acc = baseAccuracy >= 0.0
                ? Math.max(0.0, Math.min(1.0, baseAccuracy))
                : HitComponent.INHERIT_MOVE_ACCURACY;
            List<StatusEffectData> effectData = onHitEffects == null || onHitEffects.isEmpty()
                ? inheritedOnHitEffects : onHitEffects;
            List<StatusEffect> effects = toStatusEffects(effectData);
            return new HitComponent(
                basePower, parsed, delayTicks, requiresPreviousConnection, avoidable,
                acc, effects);
        }

        public static HitComponentData fromHitComponent(HitComponent component) {
            HitComponentData data = new HitComponentData();
            data.basePower = component.getBasePower();
            data.tags = component.getTags().stream().map(MoveTag::name).toList();
            data.delayTicks = component.getDelayTicks();
            data.requiresPreviousConnection = component.requiresPreviousConnection();
            data.avoidable = component.isAvoidable();
            // Only persist per-hit accuracy when it is actually authored; leaving
            // baseAccuracy at its -1.0 "inherit" default keeps legacy saves clean.
            if (component.hasOwnAccuracy()) {
                data.baseAccuracy = component.getBaseAccuracy();
            }
            if (!component.getOnHitEffects().isEmpty()) {
                data.onHitEffects = component.getOnHitEffects().stream()
                    .map(MoveData::toEffectData).toList();
            }
            return data;
        }
    }

    // -------------------------------------------------------------------------
    // Status effect sub-DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusEffectData {
        /** StatusEffectType enum name */
        public String type;
        public int    durationRounds = 1;
        public int    durationTicks  = 0;
        public double magnitude      = 1.0;

        /**
         * Coded-action binding. When {@code codedAbilityKey} is set (and {@code type}
         * is blank), this row is a coded action dispatched to the matching
         * {@link com.jjktbf.model.character.coded.CodedAbilityRuntime} rather than
         * applied as a status. This is how a technique move's hardcoded effect is
         * stored on an editable effect row (self or on-hit) instead of on the move.
         */
        public String codedAbilityKey;

        /** Action interpreted by {@link #codedAbilityKey} when this effect fires. */
        public String codedAction;

        /** Action-specific target/mode, such as APPLY_TO_MOVE or CREATE_STACKS. */
        public String codedTarget;

        /** Number of stacks to create when the coded target supports stacking. */
        public Integer codedStackCount;

        /** Allow-listed integer parameters owned by the selected coded action. */
        public Map<String, Integer> codedParameters;

        /** Optional per-field CTM formulas or benchmark tables. */
        public Map<String, TechniqueMasteryProgressionData> masteryProgression;

        /**
         * Summon flavour. When set (and {@code type}/{@code codedAbilityKey} are
         * blank), this row summons the named shikigami definition when it fires
         * instead of applying a status or coded action.
         */
        public String summonCharacterId;

        @JsonIgnore
        public boolean isCoded() {
            return codedAbilityKey != null && !codedAbilityKey.isBlank();
        }

        @JsonIgnore
        public boolean isSummon() {
            return summonCharacterId != null && !summonCharacterId.isBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Derive MoveCategory from tags
    // -------------------------------------------------------------------------

    /**
     * Resolve the MoveCategory from the stored tags list.
     * Tags that don't map to MoveCategory slots (ATTACK, UTILITY, DEFENSIVE) are
     * used for BF eligibility and filtering but don't change the power formula.
     */
    public MoveCategory derivedCategory() {
        if (tags == null || tags.isEmpty()) return MoveCategory.UTILITY;

        boolean hasPhysical    = tags.contains("PHYSICAL");
        boolean hasInnate      = tags.contains("INNATE_TECHNIQUE");
        boolean hasNonInnate   = tags.contains("NON_INNATE_TECHNIQUE");
        boolean hasCe          = tags.contains("CURSED_ENERGY");
        boolean hasDefensive   = tags.contains("DEFENSIVE");
        boolean hasUtility     = tags.contains("UTILITY");

        if (hasDefensive) return MoveCategory.DEFENSIVE;
        if (hasUtility)   return MoveCategory.UTILITY;

        // Triple hybrids
        if (hasPhysical && hasInnate && hasNonInnate)
            return MoveCategory.PHYSICAL_INNATE_NON_INNATE_TECHNIQUE;

        // Double hybrids
        if (hasPhysical && hasInnate)    return MoveCategory.PHYSICAL_INNATE_TECHNIQUE;
        if (hasPhysical && hasNonInnate) return MoveCategory.PHYSICAL_NON_INNATE_TECHNIQUE;
        if (hasPhysical && hasCe)        return MoveCategory.PHYSICAL_CURSED_ENERGY;
        if (hasInnate   && hasNonInnate) return MoveCategory.INNATE_NON_INNATE_TECHNIQUE;

        // Pure
        if (hasInnate)    return MoveCategory.INNATE_TECHNIQUE;
        if (hasNonInnate) return MoveCategory.NON_INNATE_TECHNIQUE;
        if (hasPhysical)  return MoveCategory.PHYSICAL;

        // Raw cursed-energy attacks are a standalone damaging category.
        if (hasCe) return MoveCategory.CURSED_ENERGY;

        // Unknown tag combinations degrade to UTILITY.
        return MoveCategory.UTILITY;
    }

    /**
     * Resolve the {@link MovePool} from the stored tags list.
     *
     * <p>Authoritative pool derivation: Combat Arts iff the raw tag set contains
     * PHYSICAL, else Jujutsu Arts. This is read directly from {@code tags}
     * (rather than from {@link #derivedCategory()}) because category collapses
     * away the PHYSICAL tag for defensive/utility moves — e.g. a
     * {@code [PHYSICAL, DEFENSIVE]} block derives to category {@code DEFENSIVE}
     * but must still count as a Combat Art for slot purposes.
     */
    public MovePool derivedPool() {
        return MovePool.fromTags(tags);
    }

    // -------------------------------------------------------------------------
    // Defense type helpers — use these instead of raw string comparisons
    // -------------------------------------------------------------------------

    /** True if this move uses percentage-based block reduction (defenceType = BLOCK, style = PERCENTAGE). */
    public boolean isPercentageBlock() {
        return DefenseType.BLOCK.name().equals(defenseType)
            && BlockStyle.PERCENTAGE.name().equals(blockStyle);
    }

    /** True if this move uses flat damage subtraction (defenceType = BLOCK, style = FLAT). */
    public boolean isFlatBlock() {
        return DefenseType.BLOCK.name().equals(defenseType)
            && BlockStyle.FLAT.name().equals(blockStyle);
    }

    /** True if this move has any active block (defenceType = BLOCK). */
    public boolean isAnyBlock() {
        return DefenseType.BLOCK.name().equals(defenseType);
    }

    /** True if this move is a parry (defenceType = PARRY). */
    public boolean isParry() {
        return DefenseType.PARRY.name().equals(defenseType);
    }

    /** True if this move is a dodge (defenceType = DODGE). */
    public boolean isDodge() {
        return DefenseType.DODGE.name().equals(defenseType);
    }

    /** True if this move carries an active defense window (BLOCK, PARRY, or DODGE). */
    public boolean hasActiveDefense() {
        return isAnyBlock() || isParry() || isDodge();
    }

    // -------------------------------------------------------------------------
    // Conversion: MoveData → Move (domain object)
    // -------------------------------------------------------------------------

    public Move toMove() {
        MoveCategory cat = derivedCategory();
        Set<MoveTag> rawTags = parsedTags();
        validateProgressionEligibility(rawTags);

        Move.Builder b = new Move.Builder(id)
            .name(name)
            .description(description != null ? description : "")
            .category(cat)
            .pool(derivedPool())
            .basePower(basePower)
            .baseAccuracy(baseAccuracy)
            .neverMiss(neverMiss)
            .stun(stun)
            .guardBreak(guardBreak)
            .heavy(heavy)
            .potency(potency)
            .weaponRequired(weaponRequired)
            .apCost(apCost)
            .unleashPoint(unleashPoint)
            .baseCeCost(baseCeCost)
            .hasCeCost(hasCeCost != null ? hasCeCost : baseCeCost > 0)
            .minCeCost(minCeCost)
            .maxCeCost(maxCeCost)
            .defenseType(resolveDefenseType())
            .blockStyle(resolveBlockStyle())
            .blockDuration(blockDuration)
            .blockAffectedTags(blockAffectedTags)
            .blockDamageReduction(blockDamageReduction)
            .blockFlatReduction(blockFlatReduction)
            .dodgeChance(dodgeChance)
            .dodgeScope(dodgeScope)
            .parryStaggerTicks(parryStaggerTicks)
            .requiredTechniqueId(requiredTechniqueId)
            .freeMove(isFreeMove)
            .mustBeGranted(mustBeGranted)
            .moveCap(moveCap)
            .summonCharacterId(summonCharacterId)
            .aoeType(AoeType.fromName(aoeType))
            .aoeTargetCount(aoeTargetCount >= 2 ? aoeTargetCount : 2);

        if (!rawTags.isEmpty()) b.tags(rawTags);
        if (hitComponents != null && !hitComponents.isEmpty()) {
            // Legacy migration: on-hit effects used to live on the move itself.
            // Push any such legacy list onto each component that defines none of
            // its own, so saved multi-hit moves keep applying their effects per
            // connecting hit. Conversion must not mutate this repository DTO:
            // callers validate and build the same MoveData more than once.
            b.hitComponents(hitComponents.stream()
                .filter(java.util.Objects::nonNull)
                .map(component -> component.toHitComponent(onHitEffects))
                .toList());
        } else if (onHitEffects != null && !onHitEffects.isEmpty()) {
            // Legacy single-component data (no explicit hitComponents): seed the
            // synthesized fallback component via the builder so the move keeps
            // its on-hit behaviour.
            b.onHitEffects(toStatusEffects(onHitEffects));
        }
        if (prerequisites != null)  b.prerequisites(prerequisites);
        // On-hit effects now live per HitComponent — no move-level mapping here.
        if (selfEffects   != null)  b.selfEffects(toStatusEffects(selfEffects));
        if (onBlockEffects != null) b.onBlockEffects(toStatusEffects(onBlockEffects));
        if (onParryEffects != null) b.onParryEffects(toStatusEffects(onParryEffects));
        if (onDodgeEffects != null) b.onDodgeEffects(toStatusEffects(onDodgeEffects));

        return b.build();
    }

    /**
     * Resolve the {@link DefenseType}, tolerating the legacy
     * {@code PERCENTAGE_BLOCK} / {@code FLAT_BLOCK} enum names from older saves:
     * both map to {@link DefenseType#BLOCK} (with the matching {@link BlockStyle}
     * inferred for {@link #blockStyle} when it is blank).
     */
    private DefenseType resolveDefenseType() {
        String name = defenseType != null ? defenseType.trim().toUpperCase() : "NONE";
        return switch (name) {
            case "PERCENTAGE_BLOCK", "FLAT_BLOCK" -> DefenseType.BLOCK;
            default -> {
                try { yield DefenseType.valueOf(name); }
                catch (IllegalArgumentException e) { yield DefenseType.NONE; }
            }
        };
    }

    /**
     * Resolve the {@link BlockStyle} for a BLOCK move, inferring it from a legacy
     * {@code PERCENTAGE_BLOCK} / {@code FLAT_BLOCK} defence type when {@link #blockStyle}
     * is blank. Defaults to {@link BlockStyle#PERCENTAGE}.
     */
    private BlockStyle resolveBlockStyle() {
        String legacy = defenseType != null ? defenseType.trim().toUpperCase() : "";
        if ("FLAT_BLOCK".equals(legacy)) return BlockStyle.FLAT;
        if ("PERCENTAGE_BLOCK".equals(legacy)) return BlockStyle.PERCENTAGE;
        String name = blockStyle != null ? blockStyle.trim().toUpperCase() : "PERCENTAGE";
        return switch (name) {
            case "FLAT" -> BlockStyle.FLAT;
            default    -> BlockStyle.PERCENTAGE;
        };
    }

    private Set<MoveTag> parsedTags() {
        EnumSet<MoveTag> parsed = EnumSet.noneOf(MoveTag.class);
        if (tags == null) return parsed;
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            try {
                parsed.add(MoveTag.valueOf(tag.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // Unknown future tags do not prevent older clients from loading the move.
            }
        }
        return parsed;
    }

    private void validateProgressionEligibility(Set<MoveTag> rawTags) {
        if (rawTags.contains(MoveTag.INNATE_TECHNIQUE)) return;
        List<StatusEffectData> effects = new java.util.ArrayList<>();
        if (onHitEffects != null) effects.addAll(onHitEffects);
        if (selfEffects != null) effects.addAll(selfEffects);
        if (onBlockEffects != null) effects.addAll(onBlockEffects);
        if (onParryEffects != null) effects.addAll(onParryEffects);
        if (onDodgeEffects != null) effects.addAll(onDodgeEffects);
        if (hitComponents != null) {
            for (HitComponentData component : hitComponents) {
                if (component != null && component.onHitEffects != null) {
                    effects.addAll(component.onHitEffects);
                }
            }
        }
        boolean hasProgression = effects.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(effect -> effect.masteryProgression != null
                && !effect.masteryProgression.isEmpty());
        if (hasProgression) {
            throw new IllegalArgumentException(
                "Only INNATE_TECHNIQUE moves may use mastery progression.");
        }
    }

    private static List<StatusEffect> toStatusEffects(List<StatusEffectData> dtos) {
        if (dtos == null) return List.of();
        java.util.ArrayList<StatusEffect> effects = new java.util.ArrayList<>();
        for (StatusEffectData d : dtos) {
            if (d == null) continue;
            // Summon row — enqueues a shikigami onto the wielder's team when it fires.
            if (d.isSummon()) {
                if (d.isCoded()) {
                    throw new IllegalArgumentException(
                        "A summon effect row cannot also be a coded action");
                }
                StatusEffect effect = new StatusEffect(d.summonCharacterId);
                effects.add(effect);
                continue;
            }
            // Coded action row — dispatched to a compiled runtime, not applied as a status.
            if (d.isCoded()) {
                if (!com.jjktbf.model.character.coded.CodedAbilityRegistry.supportsEffect(
                    d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedStackCount)) {
                    throw new IllegalArgumentException("Invalid coded effect "
                        + d.codedAbilityKey + "/" + d.codedAction);
                }
                String parameterError = com.jjktbf.model.character.coded.CodedAbilityRegistry
                    .effectParameterValidationError(
                        d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedParameters);
                if (parameterError != null) throw new IllegalArgumentException(parameterError);
                Set<String> allowed = new java.util.LinkedHashSet<>();
                if (d.codedStackCount != null) {
                    allowed.add(TechniqueMasteryProgressions.CODED_STACK_COUNT);
                }
                if (d.codedParameters != null) allowed.addAll(d.codedParameters.keySet());
                validateEffectProgression(d, allowed);
                StatusEffect effect = StatusEffect.coded(
                    d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedStackCount,
                    d.codedParameters, d.masteryProgression);
                validateResolvedEffect(effect);
                effects.add(effect);
                continue;
            }
            if (d.type == null || d.type.isBlank()) continue;
            StatusEffectType type;
            try {
                type = StatusEffectType.fromName(d.type, d.magnitude);
            } catch (IllegalArgumentException ignored) {
                // Removed one-off and unknown statuses do not invalidate the move.
                continue;
            }
            Set<String> allowed = new java.util.LinkedHashSet<>();
            if (type.requiresTickDuration()) {
                allowed.add(TechniqueMasteryProgressions.DURATION_TICKS);
            } else {
                allowed.add(TechniqueMasteryProgressions.DURATION_ROUNDS);
                if (!type.requiresRoundDuration()) {
                    allowed.add(TechniqueMasteryProgressions.DURATION_TICKS);
                }
            }
            if (type.usesMagnitude()) allowed.add(TechniqueMasteryProgressions.MAGNITUDE);
            validateEffectProgression(d, allowed);
            StatusEffect effect = new StatusEffect(type, d.durationRounds, d.durationTicks,
                StatusEffectType.normalizeStoredMagnitude(d.type, d.magnitude),
                d.masteryProgression);
            validateResolvedEffect(effect);
            effects.add(effect);
        }
        return effects;
    }

    private static void validateEffectProgression(StatusEffectData data, Set<String> allowed) {
        String error = TechniqueMasteryProgressions.validationError(
            data.masteryProgression, allowed);
        if (error != null) throw new IllegalArgumentException(error);
    }

    private static void validateResolvedEffect(StatusEffect effect) {
        if (effect.getMasteryProgression().isEmpty()) return;
        for (int mastery = 0; mastery <= 300; mastery++) {
            try {
                StatusEffect resolved =
                    com.jjktbf.model.progression.TechniqueMasteryResolver.resolve(effect, mastery);
                if (resolved.isCoded()) {
                    String parameterError = com.jjktbf.model.character.coded.CodedAbilityRegistry
                        .effectParameterValidationError(
                            resolved.getCodedAbilityKey(), resolved.getCodedAction(),
                            resolved.getCodedTarget(), resolved.getCodedParameters());
                    if (parameterError != null) {
                        throw new IllegalArgumentException(parameterError);
                    }
                    if (resolved.getCodedStackCount() != null
                        && !com.jjktbf.model.character.coded.CodedAbilityRegistry.supportsEffect(
                            resolved.getCodedAbilityKey(), resolved.getCodedAction(),
                            resolved.getCodedTarget(), resolved.getCodedStackCount())) {
                        throw new IllegalArgumentException("Invalid resolved coded stack count");
                    }
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                    "Invalid move-effect mastery progression at CTM " + mastery + ": "
                        + exception.getMessage(), exception);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Conversion: Move (domain object) → MoveData
    // -------------------------------------------------------------------------

    /**
     * Reconstruct a MoveData from a {@link Move}.
     *
     * <p>Raw tags loaded from a {@code MoveData} are retained by {@link Move}, so
     * defensive and utility moves keep their underlying physical, CE, or technique
     * nature when saved back to data.
     */
    public static MoveData fromMove(Move move) {
        MoveData d = new MoveData();
        d.id                  = move.getId();
        d.name                = move.getName();
        d.description         = move.getDescription();

        List<String> tagList = move.getTags().stream()
            .map(MoveTag::name)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        MoveCategory cat = move.getCategory();
        if (tagList.isEmpty() && cat != null) {
            cat.getTags().forEach(t -> tagList.add(t.name()));
        }
        // Add ATTACK if it's a damaging move (non-defensive, non-utility with basePower > 0)
        if (move.getBasePower() > 0
                && cat != MoveCategory.DEFENSIVE
                && cat != MoveCategory.UTILITY
                && !tagList.contains(MoveTag.ATTACK.name())) {
            tagList.add("ATTACK");
        }
        d.tags = tagList;

        d.basePower           = move.getBasePower();
        // Zero-power legacy attacks expose a synthetic fallback component at
        // runtime. Keep those in the legacy shape instead of serializing an
        // explicit component that new authoring validation correctly rejects.
        if (move.getBasePower() > 0) {
            d.hitComponents = move.getHitComponents().stream()
                .map(HitComponentData::fromHitComponent)
                .toList();
        } else if (!move.getHitComponents().isEmpty()
            && !move.getHitComponents().get(0).getOnHitEffects().isEmpty()) {
            d.onHitEffects = move.getHitComponents().get(0).getOnHitEffects().stream()
                .map(MoveData::toEffectData)
                .toList();
        }
        d.baseAccuracy        = move.getBaseAccuracy();
        d.neverMiss           = move.isNeverMiss();
        d.stun                = move.isStun();
        d.guardBreak          = move.isGuardBreak();
        d.heavy               = move.isHeavy();
        d.potency             = move.getPotency();
        d.weaponRequired      = move.isWeaponRequired();
        d.apCost              = move.getApCost();
        d.unleashPoint        = move.getUnleashPoint();
        d.baseCeCost          = move.getBaseCeCost();
        d.hasCeCost           = move.hasCeCost();
        d.minCeCost           = move.getMinCeCost();
        d.maxCeCost           = move.getMaxCeCost();
        d.defenseType           = move.getDefenseType().name();
        d.blockStyle            = move.getBlockStyle() != null ? move.getBlockStyle().name() : "PERCENTAGE";
        d.blockDuration         = move.getBlockDuration();
        d.blockAffectedTags     = move.getBlockAffectedTags() != null
                                    ? new java.util.ArrayList<>(move.getBlockAffectedTags()) : null;
        d.blockDamageReduction  = move.getBlockDamageReduction();
        d.blockFlatReduction    = move.getBlockFlatReduction();
        d.dodgeChance           = move.getDodgeChance();
        d.dodgeScope            = move.getDodgeScope();
        d.parryStaggerTicks     = move.getParryStaggerTicks();
        d.requiredTechniqueId = move.getRequiredTechniqueId();
        d.isFreeMove          = move.isFreeMove();
        d.mustBeGranted       = move.mustBeGranted();
        d.moveCap             = move.getMoveCap();
        d.summonCharacterId   = move.getSummonCharacterId();
        if (move.getAoeType() != null) {
            d.aoeType         = move.getAoeType().name();
            d.aoeTargetCount  = move.getAoeTargetCount();
        }
        d.prerequisites       = move.getPrerequisites().isEmpty() ? null
                                    : new java.util.LinkedHashMap<>(move.getPrerequisites());

        // Positive-power on-hit effects are serialized per HitComponent. A
        // zero-power attack keeps its synthetic component in the legacy
        // move-level shape populated above.
        if (!move.getSelfEffects().isEmpty()) {
            d.selfEffects = move.getSelfEffects().stream().map(MoveData::toEffectData).toList();
        }
        if (!move.getOnBlockEffects().isEmpty()) {
            d.onBlockEffects = move.getOnBlockEffects().stream().map(MoveData::toEffectData).toList();
        }
        if (!move.getOnParryEffects().isEmpty()) {
            d.onParryEffects = move.getOnParryEffects().stream().map(MoveData::toEffectData).toList();
        }
        if (!move.getOnDodgeEffects().isEmpty()) {
            d.onDodgeEffects = move.getOnDodgeEffects().stream().map(MoveData::toEffectData).toList();
        }
        return d;
    }

    /**
     * Serialize a single {@link StatusEffect} back to its DTO. A coded-action row
     * is emitted with its coded fields and a null {@code type}; a status row is
     * emitted with its {@code StatusEffectType} and the coded fields left blank.
     */
    private static StatusEffectData toEffectData(StatusEffect e) {
        StatusEffectData sd = new StatusEffectData();
        if (e.isSummon()) {
            sd.summonCharacterId = e.getSummonCharacterId();
            return sd;
        }
        if (e.isCoded()) {
            sd.codedAbilityKey = e.getCodedAbilityKey();
            sd.codedAction     = e.getCodedAction();
            sd.codedTarget     = e.getCodedTarget();
            sd.codedStackCount = e.getCodedStackCount();
            sd.codedParameters = TechniqueMasteryProgressions.copyIntegers(e.getCodedParameters());
            sd.masteryProgression = TechniqueMasteryProgressions.copy(e.getMasteryProgression());
            return sd;
        }
        sd.type           = e.getType().name();
        sd.durationRounds = e.getDurationRounds();
        sd.durationTicks  = e.getDurationTicks();
        sd.magnitude      = e.getMagnitude();
        sd.masteryProgression = TechniqueMasteryProgressions.copy(e.getMasteryProgression());
        return sd;
    }
}
