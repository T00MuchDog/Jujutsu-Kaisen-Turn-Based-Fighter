package com.jjktbf.model.character;

import com.jjktbf.model.move.MoveCategory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth for move-slot budget calculations.
 *
 * Previously this logic was duplicated between:
 *   - Character.getSlotCount()       (used at game-load time)
 *   - CharacterEditorMain.getSlotBudget() (used during character editing)
 *
 * Both now delegate here. Adding a new slot-type stat only requires one change.
 */
public final class SlotBudgetEnforcer {

    private SlotBudgetEnforcer() {}

    /**
     * Returns the number of move slots available for the given MoveCategory,
     * given the character's combat and base stats.
     *
     * DEFENSIVE and UTILITY categories are unlimited (not slot-gated) — callers
     * should check for these before calling this method if they want to skip gating.
     */
    public static int slotBudgetFor(CombatStats cs, CharacterStats stats, MoveCategory cat) {
        return switch (cat) {
            case PHYSICAL             -> cs.getPhysicalMoveSlots();
            case INNATE_TECHNIQUE     -> cs.getCursedTechniqueSlots();
            case NON_INNATE_TECHNIQUE,
                 CURSED_ENERGY        -> cs.getJujutsuTechniqueSlots();
            default                   -> cs.hybridSlots(stats, cat);
        };
    }

    /**
     * Returns true if the given category is slot-gated (i.e. not DEFENSIVE or UTILITY).
     */
    public static boolean isSlotGated(MoveCategory cat) {
        return cat != MoveCategory.DEFENSIVE && cat != MoveCategory.UTILITY;
    }

    /**
     * Counts slot usage from a set of already-assigned move categories.
     * Only slot-gated categories are counted.
     *
     * @param assignedCategories  categories of moves already assigned (may include duplicates)
     * @return mutable EnumMap of category → slots used
     */
    public static Map<MoveCategory, Integer> countUsage(Iterable<MoveCategory> assignedCategories) {
        Map<MoveCategory, Integer> used = new EnumMap<>(MoveCategory.class);
        for (MoveCategory cat : assignedCategories) {
            if (isSlotGated(cat)) {
                used.merge(cat, 1, Integer::sum);
            }
        }
        return used;
    }
}
