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
     * defender's blocking defensive moves (PERCENTAGE_BLOCK / FLAT_BLOCK).
     */
    public boolean guardBreak     = false;

    /**
     * Backs the HEAVY move tag. When true, an action segment carrying this move
     * cannot be stunned by a STUN-tagged hit.
     */
    public boolean heavy          = false;

    public int     apCost;
    public int     unleashPoint;

    public int     baseCeCost     = 0;
    /** Null in older saves; then inferred from a positive base CE cost on load. */
    public Boolean hasCeCost;
    public int     minCeCost      = 0;
    public int     maxCeCost      = 0;

    /** InterruptType enum name */
    public String  interruptType  = "NONE";

    /** DefenseType enum name */
    public String  defenseType    = "NONE";

    /** Block-specific fields (used when defenseType = PERCENTAGE_BLOCK or FLAT_BLOCK) */
    /** Duration in AP ticks. 0 = use move's apCost. -1 = end of round. */
    public int     blockDuration = 0;
    /** Tags this block affects. Null = all damage types. */
    public List<String> blockAffectedTags;
    /** PERCENTAGE_BLOCK only: percentage of damage reduced (0-100). 100 = full block. */
    public int     blockDamageReduction = 100;
    /** FLAT_BLOCK only: flat damage amount subtracted from incoming attacks. */
    public int     blockFlatReduction = 0;

    /** List of on-hit StatusEffect descriptors */
    public List<StatusEffectData> onHitEffects;

    /** List of self StatusEffect descriptors */
    public List<StatusEffectData> selfEffects;

    /** Allow-listed compiled ability key for a move action outside normal status effects. */
    public String codedAbilityKey;

    /** Action interpreted by {@link #codedAbilityKey} when this move unleashes. */
    public String codedAction;

    /** Prerequisite stats: {"strength": 80, "speed": 60, ...} */
    public Map<String, Integer> prerequisites;

    /**
     * Human-readable technique name this move requires (e.g. "Shrine", "Blood Manipulation").
     * Null means no technique restriction.
     * The numeric technique ID is resolved via TechniqueRepository at load time.
     */
    public String  requiredTechniqueId;

    public boolean isFreeMove = false;

    @JsonIgnore
    public boolean isCoded() {
        return codedAbilityKey != null && !codedAbilityKey.isBlank();
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

    /** True if this move uses percentage-based block reduction. */
    public boolean isPercentageBlock() {
        return DefenseType.PERCENTAGE_BLOCK.name().equals(defenseType);
    }

    /** True if this move uses flat damage subtraction. */
    public boolean isFlatBlock() {
        return DefenseType.FLAT_BLOCK.name().equals(defenseType);
    }

    /** True if this move has any active block (PERCENTAGE_BLOCK or FLAT_BLOCK). */
    public boolean isAnyBlock() {
        return isPercentageBlock() || isFlatBlock();
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
            .apCost(apCost)
            .unleashPoint(unleashPoint)
            .baseCeCost(baseCeCost)
            .hasCeCost(hasCeCost != null ? hasCeCost : baseCeCost > 0)
            .minCeCost(minCeCost)
            .maxCeCost(maxCeCost)
            .interruptType(InterruptType.valueOf(interruptType != null ? interruptType : "NONE"))
            .defenseType(DefenseType.valueOf(defenseType != null ? defenseType : "NONE"))
            .blockDuration(blockDuration)
            .blockAffectedTags(blockAffectedTags)
            .blockDamageReduction(blockDamageReduction)
            .blockFlatReduction(blockFlatReduction)
            .codedAbilityKey(codedAbilityKey)
            .codedAction(codedAction)
            .requiredTechniqueId(requiredTechniqueId)
            .freeMove(isFreeMove);

        if (!rawTags.isEmpty()) b.tags(rawTags);
        if (prerequisites != null)  b.prerequisites(prerequisites);
        if (onHitEffects  != null)  b.onHitEffects(toStatusEffects(onHitEffects));
        if (selfEffects   != null)  b.selfEffects(toStatusEffects(selfEffects));

        return b.build();
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
            if (d == null || d.type == null || d.type.isBlank()) continue;
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
        d.baseAccuracy        = move.getBaseAccuracy();
        d.neverMiss           = move.isNeverMiss();
        d.stun                = move.isStun();
        d.guardBreak          = move.isGuardBreak();
        d.heavy               = move.isHeavy();
        d.apCost              = move.getApCost();
        d.unleashPoint        = move.getUnleashPoint();
        d.baseCeCost          = move.getBaseCeCost();
        d.hasCeCost           = move.hasCeCost();
        d.minCeCost           = move.getMinCeCost();
        d.maxCeCost           = move.getMaxCeCost();
        d.interruptType         = move.getInterruptType().name();
        d.defenseType           = move.getDefenseType().name();
        d.blockDuration         = move.getBlockDuration();
        d.blockAffectedTags     = move.getBlockAffectedTags() != null
                                    ? new java.util.ArrayList<>(move.getBlockAffectedTags()) : null;
        d.blockDamageReduction  = move.getBlockDamageReduction();
        d.blockFlatReduction    = move.getBlockFlatReduction();
        d.codedAbilityKey       = move.getCodedAbilityKey();
        d.codedAction           = move.getCodedAction();
        d.requiredTechniqueId = move.getRequiredTechniqueId();
        d.isFreeMove          = move.isFreeMove();
        d.prerequisites       = move.getPrerequisites().isEmpty() ? null
                                    : new java.util.LinkedHashMap<>(move.getPrerequisites());

        if (!move.getOnHitEffects().isEmpty()) {
            d.onHitEffects = move.getOnHitEffects().stream().map(e -> {
                StatusEffectData sd = new StatusEffectData();
                sd.type           = e.getType().name();
                sd.durationRounds = e.getDurationRounds();
                sd.durationTicks  = e.getDurationTicks();
                sd.magnitude      = e.getMagnitude();
                return sd;
            }).toList();
        }
        if (!move.getSelfEffects().isEmpty()) {
            d.selfEffects = move.getSelfEffects().stream().map(e -> {
                StatusEffectData sd = new StatusEffectData();
                sd.type           = e.getType().name();
                sd.durationRounds = e.getDurationRounds();
                sd.durationTicks  = e.getDurationTicks();
                sd.magnitude      = e.getMagnitude();
                return sd;
            }).toList();
        }
        return d;
    }
}
