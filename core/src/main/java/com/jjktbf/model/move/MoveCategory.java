package com.jjktbf.model.move;

import java.util.EnumSet;
import java.util.Set;

/**
 * Canonical move categories derived from tag combinations.
 *
 * Each category:
 *  - Specifies which MoveTag set it corresponds to.
 *  - Is used to select the correct Power formula during damage calculation.
 *  - Determines which stat (Combat Ability / Jujutsu Skill / CTM) gates the move slot.
 *  - Determines Black Flash eligibility.
 *
 * Move slot gating rule:
 *   Hybrid moves consume a slot from the LESSER of the governing stats.
 */
public enum MoveCategory {

    // -------------------------------------------------------------------------
    // Pure categories
    // -------------------------------------------------------------------------

    /** Physical only. Slot governed by CombatAbility. */
    PHYSICAL(
        EnumSet.of(MoveTag.PHYSICAL),
        SlotStat.COMBAT_ABILITY,
        false   // No BF — no CE component
    ),

    /**
     * Raw cursed-energy attack only (no technique, no physical contact).
     * A dedicated damaging category for pure-CE output. Slot governed by
     * JujutsuSkill. Black-Flash ineligible (no physical contact component).
     */
    CURSED_ENERGY(
        EnumSet.of(MoveTag.CURSED_ENERGY),
        SlotStat.JUJUTSU_SKILL,
        false
    ),

    /** Innate Technique only (CE implied). Slot governed by CursedTechniqueMastery. */
    INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.INNATE_TECHNIQUE),
        SlotStat.CURSED_TECHNIQUE_MASTERY,
        false    // BF ineligible (No physical contact)
    ),

    /** Non-Innate Technique only (CE implied). Slot governed by JujutsuSkill. */
    NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.NON_INNATE_TECHNIQUE),
        SlotStat.JUJUTSU_SKILL,
        false    // BF ineligible (No physical contact)
    ),

    // -------------------------------------------------------------------------
    // Hybrid categories
    // -------------------------------------------------------------------------

    /** Physical + CursedEnergy. 3:1 CE:Physical. Slot: lesser of CA / JS. */
    PHYSICAL_CURSED_ENERGY(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY),
        SlotStat.LESSER_CA_JS,
        true
    ),

    /** Physical + InnateT. 4:1 InnateT:Physical. Slot: lesser of CA / CTM. */
    PHYSICAL_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.INNATE_TECHNIQUE),
        SlotStat.LESSER_CA_CTM,
        true
    ),

    /** Physical + NonInnateT. 3:1 NonInnateT:Physical. Slot: lesser of CA / JS. */
    PHYSICAL_NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.NON_INNATE_TECHNIQUE),
        SlotStat.LESSER_CA_JS,
        true
    ),

    /** InnateT + NonInnateT. 3:2 InnateT:NonInnateT. Slot: lesser of CTM / JS. */
    INNATE_NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE),
        SlotStat.LESSER_CTM_JS,
        false
    ),

    /** Physical + InnateT + NonInnateT. 1:3:2. Slot: lesser of all three. */
    PHYSICAL_INNATE_NON_INNATE_TECHNIQUE(
        EnumSet.of(MoveTag.PHYSICAL, MoveTag.INNATE_TECHNIQUE, MoveTag.NON_INNATE_TECHNIQUE),
        SlotStat.LESSER_ALL_THREE,
        true
    ),

    // -------------------------------------------------------------------------
    // Non-damaging
    // -------------------------------------------------------------------------

    /** Utility / status — no damage formula needed. */
    UTILITY(
        EnumSet.of(MoveTag.UTILITY),
        SlotStat.NONE,
        false
    ),

    /** Defensive — block, guard, parry. */
    DEFENSIVE(
        EnumSet.of(MoveTag.DEFENSIVE),
        SlotStat.NONE,
        false
    );

    // -------------------------------------------------------------------------

    private final Set<MoveTag> tags;
    private final SlotStat slotStat;
    private final boolean blackFlashEligible;

    MoveCategory(Set<MoveTag> tags, SlotStat slotStat, boolean blackFlashEligible) {
        this.tags                = tags;
        this.slotStat            = slotStat;
        this.blackFlashEligible  = blackFlashEligible;
    }

    public Set<MoveTag> getTags()           { return tags; }
    public SlotStat getSlotStat()           { return slotStat; }
    public boolean isBlackFlashEligible()   { return blackFlashEligible; }

    /** Resolve the MoveCategory from a set of tags. */
    public static MoveCategory fromTags(Set<MoveTag> tags) {
        for (MoveCategory cat : values()) {
            if (cat.tags.equals(tags)) return cat;
        }
        throw new IllegalArgumentException("No MoveCategory defined for tag set: " + tags);
    }

    // -------------------------------------------------------------------------
    // Slot stat descriptor
    // -------------------------------------------------------------------------

    /**
     * Which stat (or combination) gates the number of learnable moves in this category.
     * LESSER_* means the character's weaker of the two/three stats determines the cap.
     */
    public enum SlotStat {
        COMBAT_ABILITY,
        JUJUTSU_SKILL,
        CURSED_TECHNIQUE_MASTERY,
        LESSER_CA_JS,
        LESSER_CA_CTM,
        LESSER_CTM_JS,
        LESSER_ALL_THREE,
        NONE
    }
}
