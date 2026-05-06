package com.jjktbf.model.move;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Plain data object (DTO) for serialising/deserialising a Move to/from JSON.
 *
 * ID: 6-digit zero-padded integer string, auto-assigned by MoveRepository.
 *
 * Category is derived at runtime from the tags list — it is not stored separately.
 * Tags is the canonical representation; MoveCategory is computed via MoveCategory.fromTags().
 *
 * requiredTechniqueId has been replaced by requiredTechniqueName (plain string, e.g. "Shrine").
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
    public double  baseAccuracy   = 1.0;
    public boolean neverMiss      = false;

    public int     apCost;
    public int     unleashPoint;

    public int     baseCeCost     = 0;
    public int     minCeCost      = 0;
    public int     maxCeCost      = 0;

    /** InterruptType enum name */
    public String  interruptType  = "NONE";

    /** DefenseType enum name */
    public String  defenseType    = "NONE";

    /** Block-specific fields (used when defenseType = BLOCK or FLAT_BLOCK) */
    /** Duration in AP ticks. 0 = use move's apCost. -1 = end of round. */
    public int     blockDuration = 0;
    /** Tags this block affects. Null = all damage types. */
    public List<String> blockAffectedTags;
    /** BLOCK only: percentage of damage reduced (0-100). 100 = full block. */
    public int     blockDamageReduction = 100;
    /** FLAT_BLOCK only: flat damage amount subtracted from incoming attacks. */
    public int     blockFlatReduction = 0;

    /** List of on-hit StatusEffect descriptors */
    public List<StatusEffectData> onHitEffects;

    /** List of self StatusEffect descriptors */
    public List<StatusEffectData> selfEffects;

    /** Prerequisite stats: {"strength": 80, "speed": 60, ...} */
    public Map<String, Integer> prerequisites;

    /**
     * Human-readable technique name this move requires (e.g. "Shrine", "Blood Manipulation").
     * Null means no technique restriction.
     * The numeric technique ID is resolved via TechniqueRepository at load time.
     */
    public String  requiredTechniqueName;

    public boolean isGuaranteedMove = false;

    // -------------------------------------------------------------------------
    // Status effect sub-DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatusEffectData {
        /** StatusEffectType enum name */
        public String type;
        public int    durationRounds = 1;
        public double magnitude      = 1.0;
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

        // CE-only moves are not supported standalone; treat as UTILITY
        return MoveCategory.UTILITY;
    }

    // -------------------------------------------------------------------------
    // Conversion: MoveData → Move (domain object)
    // -------------------------------------------------------------------------

    public Move toMove() {
        MoveCategory cat = derivedCategory();

        Move.Builder b = new Move.Builder(id)
            .name(name)
            .description(description != null ? description : "")
            .category(cat)
            .basePower(basePower)
            .baseAccuracy(baseAccuracy)
            .neverMiss(neverMiss)
            .apCost(apCost)
            .unleashPoint(unleashPoint)
            .baseCeCost(baseCeCost)
            .minCeCost(minCeCost)
            .maxCeCost(maxCeCost)
            .interruptType(InterruptType.valueOf(interruptType != null ? interruptType : "NONE"))
            .defenseType(DefenseType.valueOf(defenseType != null ? defenseType : "NONE"))
            .blockDuration(blockDuration)
            .blockAffectedTags(blockAffectedTags)
            .blockDamageReduction(blockDamageReduction)
            .blockFlatReduction(blockFlatReduction)
            .requiredTechniqueId(requiredTechniqueName) // still stored as requiredTechniqueId in Move domain
            .guaranteedMove(isGuaranteedMove);

        if (prerequisites != null)  b.prerequisites(prerequisites);
        if (onHitEffects  != null)  b.onHitEffects(toStatusEffects(onHitEffects));
        if (selfEffects   != null)  b.selfEffects(toStatusEffects(selfEffects));

        return b.build();
    }

    private static List<StatusEffect> toStatusEffects(List<StatusEffectData> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream()
            .filter(d -> d.type != null && !d.type.isBlank())
            .map(d -> new StatusEffect(StatusEffectType.valueOf(d.type.toUpperCase()), d.durationRounds, d.magnitude))
            .toList();
    }

    // -------------------------------------------------------------------------
    // Conversion: Move (domain object) → MoveData
    // -------------------------------------------------------------------------

    public static MoveData fromMove(Move move) {
        MoveData d = new MoveData();
        d.id                  = move.getId();
        d.name                = move.getName();
        d.description         = move.getDescription();

        // Build tags list from category + other flags
        List<String> tagList = new java.util.ArrayList<>();
        MoveCategory cat = move.getCategory();
        // Expand category back to constituent tags
        cat.getTags().forEach(t -> tagList.add(t.name()));
        // Add ATTACK if it's a damaging move (non-defensive, non-utility with basePower > 0)
        if (move.getBasePower() > 0
                && cat != MoveCategory.DEFENSIVE
                && cat != MoveCategory.UTILITY) {
            tagList.add("ATTACK");
        }
        d.tags = tagList;

        d.basePower           = move.getBasePower();
        d.baseAccuracy        = move.getBaseAccuracy();
        d.neverMiss           = move.isNeverMiss();
        d.apCost              = move.getApCost();
        d.unleashPoint        = move.getUnleashPoint();
        d.baseCeCost          = move.getBaseCeCost();
        d.minCeCost           = move.getMinCeCost();
        d.maxCeCost           = move.getMaxCeCost();
        d.interruptType         = move.getInterruptType().name();
        d.defenseType           = move.getDefenseType().name();
        d.blockDuration         = move.getBlockDuration();
        d.blockAffectedTags     = move.getBlockAffectedTags() != null
                                    ? new java.util.ArrayList<>(move.getBlockAffectedTags()) : null;
        d.blockDamageReduction  = move.getBlockDamageReduction();
        d.blockFlatReduction    = move.getBlockFlatReduction();
        d.requiredTechniqueName = move.getRequiredTechniqueId(); // domain still uses id field
        d.isGuaranteedMove    = move.isGuaranteedMove();
        d.prerequisites       = move.getPrerequisites().isEmpty() ? null
                                    : new java.util.LinkedHashMap<>(move.getPrerequisites());

        if (!move.getOnHitEffects().isEmpty()) {
            d.onHitEffects = move.getOnHitEffects().stream().map(e -> {
                StatusEffectData sd = new StatusEffectData();
                sd.type           = e.getType().name();
                sd.durationRounds = e.getDurationRounds();
                sd.magnitude      = e.getMagnitude();
                return sd;
            }).toList();
        }
        if (!move.getSelfEffects().isEmpty()) {
            d.selfEffects = move.getSelfEffects().stream().map(e -> {
                StatusEffectData sd = new StatusEffectData();
                sd.type           = e.getType().name();
                sd.durationRounds = e.getDurationRounds();
                sd.magnitude      = e.getMagnitude();
                return sd;
            }).toList();
        }
        return d;
    }
}
