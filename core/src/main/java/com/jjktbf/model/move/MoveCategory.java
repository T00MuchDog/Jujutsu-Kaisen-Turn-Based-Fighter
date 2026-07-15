package com.jjktbf.model.move;

import java.util.EnumSet;
import java.util.Set;

/**
 * Canonical move categories derived from tag combinations.
 *
 * Each category:
 *  - Specifies which MoveTag set it corresponds to.
 *  - Is used to select the correct Power formula during damage calculation.
 *  - Determines Black Flash eligibility.
 *
 * Slot assignment is NOT determined here — it is governed by {@link MovePool}
 * (Combat Arts vs Jujutsu Arts), which keys on the raw PHYSICAL tag. Category
 * and pool are orthogonal: a {@code [PHYSICAL, DEFENSIVE]} block and a
 * DEFENSIVE-only block both derive to category {@link #DEFENSIVE} here, but
 * resolve to different pools via {@code MovePool.fromTags(...)}.
 */
public enum MoveCategory {

    // -------------------------------------------------------------------------
    // Pure categories
    // -------------------------------------------------------------------------

    /** Physical only. */
    PHYSICAL(
        EnumSet.of(MoveTag.PHYSICAL),
        false   // No BF — no CE component
    ),

    /**
     * Raw cursed-energy attack only (no technique, no physical contact).
     * A dedicated damaging category for pure-CE output.
     * Black-Flash ineligible (no physical contact component).
     */
    CURSED_ENERGY(
        EnumSet.of(MoveTag.CURSED_ENERGY),
        false
    ),

    /** Innate Technique only (CE implied). */
    INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.INNATE_TECHNIQUE),
        false    // BF ineligible (No physical contact)
    ),

    /** Non-Innate Technique only (CE implied). */
    NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.NON_INNATE_TECHNIQUE),
        false    // BF ineligible (No physical contact)
    ),

    // -------------------------------------------------------------------------
    // Hybrid categories
    // -------------------------------------------------------------------------

    /** Physical + CursedEnergy. 3:1 CE:Physical. */
    PHYSICAL_CURSED_ENERGY(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY),
        true
    ),

    /** Physical + InnateT. 4:1 InnateT:Physical. */
    PHYSICAL_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.INNATE_TECHNIQUE),
        true
    ),

    /** Physical + NonInnateT. 3:1 NonInnateT:Physical. */
    PHYSICAL_NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.NON_INNATE_TECHNIQUE),
        true
    ),

    /** InnateT + NonInnateT. 3:2 InnateT:NonInnateT. */
    INNATE_NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE),
        false
    ),

    /** Physical + InnateT + NonInnateT. 1:3:2. */
    PHYSICAL_INNATE_NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE),
        true
    ),

    // -------------------------------------------------------------------------
    // Non-damaging
    // -------------------------------------------------------------------------

    /** Utility / status — no damage formula needed. */
    UTILITY(
        EnumSet.of(MoveTag.UTILITY),
        false
    ),

    /** Defensive — block, guard, parry. */
    DEFENSIVE(
        EnumSet.of(MoveTag.DEFENSIVE),
        false
    );

    // -------------------------------------------------------------------------

    private final Set<MoveTag> tags;
    private final boolean blackFlashEligible;

    MoveCategory(Set<MoveTag> tags, boolean blackFlashEligible) {
        this.tags                = tags;
        this.blackFlashEligible  = blackFlashEligible;
    }

    public Set<MoveTag> getTags()           { return tags; }
    public boolean isBlackFlashEligible()   { return blackFlashEligible; }

    /** Resolve the MoveCategory from a set of tags. */
    public static MoveCategory fromTags(Set<MoveTag> tags) {
        for (MoveCategory cat : values()) {
            if (cat.tags.equals(tags)) return cat;
        }
        throw new IllegalArgumentException("No MoveCategory defined for tag set: " + tags);
    }
}
