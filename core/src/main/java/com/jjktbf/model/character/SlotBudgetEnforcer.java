package com.jjktbf.model.character;

import com.jjktbf.model.move.MovePool;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth for move-slot budget calculations.
 *
 * <p>There are two move pools, each granted slots by a single stat:
 * <ul>
 *   <li>{@link MovePool#COMBAT_ARTS} — moves containing the PHYSICAL tag;
 *       slots granted by the Combat Ability stat.</li>
 *   <li>{@link MovePool#JUJUTSU_ARTS} — moves without the PHYSICAL tag;
 *       slots granted by the Jujutsu Skill stat.</li>
 * </ul>
 *
 * <p>Every non-free move consumes a slot in its pool; the only exemption from
 * the slot system is the {@code isFreeMove} flag (e.g. Basic Punch / Basic
 * Block). Offensive, defensive and utility moves alike take up slots.
 *
 * <p>Previously this logic was duplicated between Character.getSlotCount() and
 * CharacterEditorMain.getSlotBudget(); both now delegate here. Adding a new
 * pool only requires one change.
 */
public final class SlotBudgetEnforcer {

    private SlotBudgetEnforcer() {}

    /**
     * Returns the number of move slots available for the given {@link MovePool},
     * given the character's derived combat stats.
     */
    public static int slotBudgetFor(CombatStats cs, MovePool pool) {
        return switch (pool) {
            case COMBAT_ARTS  -> cs.getCombatArtsSlots();
            case JUJUTSU_ARTS -> cs.getJujutsuArtsSlots();
        };
    }

    /**
     * Counts slot usage from a set of already-assigned move pools.
     *
     * @param assignedPools  pools of moves already assigned (may include duplicates)
     * @return mutable EnumMap of pool → slots used
     */
    public static Map<MovePool, Integer> countUsage(Iterable<MovePool> assignedPools) {
        Map<MovePool, Integer> used = new EnumMap<>(MovePool.class);
        for (MovePool pool : assignedPools) {
            used.merge(pool, 1, Integer::sum);
        }
        return used;
    }
}
