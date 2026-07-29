package com.jjktbf.model.move;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

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
    /** Tags this block affects. Null = all damage types. (BLOCK only) */
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

    /** Cannot be assigned directly; an ability must add this move to the character. */
    public boolean mustBeGranted = false;

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

        public HitComponent toHitComponent() {
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
            return new HitComponent(
                basePower, parsed, delayTicks, requiresPreviousConnection, avoidable);
        }

        public static HitComponentData fromHitComponent(HitComponent component) {
            HitComponentData data = new HitComponentData();
            data.basePower = component.getBasePower();
            data.tags = component.getTags().stream().map(MoveTag::name).toList();
            data.delayTicks = component.getDelayTicks();
            data.requiresPreviousConnection = component.requiresPreviousConnection();
            data.avoidable = component.isAvoidable();
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

        @JsonIgnore
        public boolean isCoded() {
            return codedAbilityKey != null && !codedAbilityKey.isBlank();
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
            .mustBeGranted(mustBeGranted);

        if (!rawTags.isEmpty()) b.tags(rawTags);
        if (hitComponents != null) {
            b.hitComponents(hitComponents.stream()
                .filter(java.util.Objects::nonNull)
                .map(HitComponentData::toHitComponent)
                .toList());
        }
        if (prerequisites != null)  b.prerequisites(prerequisites);
        if (onHitEffects  != null)  b.onHitEffects(toStatusEffects(onHitEffects));
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

    private static List<StatusEffect> toStatusEffects(List<StatusEffectData> dtos) {
        if (dtos == null) return List.of();
        java.util.ArrayList<StatusEffect> effects = new java.util.ArrayList<>();
        for (StatusEffectData d : dtos) {
            if (d == null) continue;
            // Coded action row — dispatched to a compiled runtime, not applied as a status.
            if (d.isCoded()) {
                if (!com.jjktbf.model.character.coded.CodedAbilityRegistry.supportsEffect(
                    d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedStackCount)) {
                    throw new IllegalArgumentException("Invalid coded effect "
                        + d.codedAbilityKey + "/" + d.codedAction);
                }
                effects.add(StatusEffect.coded(
                    d.codedAbilityKey, d.codedAction, d.codedTarget, d.codedStackCount));
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
            effects.add(new StatusEffect(type, d.durationRounds, d.durationTicks,
                StatusEffectType.normalizeStoredMagnitude(d.type, d.magnitude)));
        }
        return effects;
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
        d.prerequisites       = move.getPrerequisites().isEmpty() ? null
                                    : new java.util.LinkedHashMap<>(move.getPrerequisites());

        if (!move.getOnHitEffects().isEmpty()) {
            d.onHitEffects = move.getOnHitEffects().stream().map(MoveData::toEffectData).toList();
        }
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
        if (e.isCoded()) {
            sd.codedAbilityKey = e.getCodedAbilityKey();
            sd.codedAction     = e.getCodedAction();
            sd.codedTarget     = e.getCodedTarget();
            sd.codedStackCount = e.getCodedStackCount();
            return sd;
        }
        sd.type           = e.getType().name();
        sd.durationRounds = e.getDurationRounds();
        sd.durationTicks  = e.getDurationTicks();
        sd.magnitude      = e.getMagnitude();
        return sd;
    }
}
