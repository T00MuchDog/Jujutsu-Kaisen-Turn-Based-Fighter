package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpponentIntelTest {

    @Test
    void physicalOnlyWhenArsenalHasNoCursedEnergy() {
        BattleCombatant opp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("punch", 20, 10),
            AIFixtures.rangedAttack("kick", 20, 10));

        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        assertTrue(intel.physicalOnly, "no CE attack => physical-only arsenal");
        assertFalse(intel.hasCursedEnergy);
        assertEquals(2, intel.attacks.size());
    }

    @Test
    void notPhysicalOnlyWhenACursedEnergyAttackExists() {
        BattleCombatant opp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("punch", 20, 10),
            AIFixtures.ceAttack("ceFist", 30, 10));

        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        assertFalse(intel.physicalOnly);
        assertTrue(intel.hasCursedEnergy);
    }

    @Test
    void pureCursedEnergyAttackAlsoMakesArsenalNonPhysical() {
        // A pure CE blast isn't "reinforcement", but it still means a physical-only
        // block won't cover everything the opponent can throw.
        BattleCombatant opp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("punch", 20, 10),
            AIFixtures.pureCeAttack("blast", 30, 10));

        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        assertFalse(intel.physicalOnly);
        assertTrue(intel.hasCursedEnergy);
    }

    @Test
    void blocksPhysicalOnlyWhenNoBlockCoversCursedEnergy() {
        BattleCombatant physicalBlocker = AIFixtures.sorcerer("opp",
            AIFixtures.block("phyBlock", List.of("PHYSICAL")),
            AIFixtures.meleeAttack("punch", 20, 10));
        assertTrue(OpponentIntel.forOpponent(physicalBlocker).blocksPhysicalOnly,
            "a [PHYSICAL]-only block can't stop CE");

        BattleCombatant ceBlocker = AIFixtures.sorcerer("opp",
            AIFixtures.block("ceBlock", List.of("PHYSICAL", "CURSED_ENERGY")),
            AIFixtures.meleeAttack("punch", 20, 10));
        assertFalse(OpponentIntel.forOpponent(ceBlocker).blocksPhysicalOnly);

        BattleCombatant blanketBlocker = AIFixtures.sorcerer("opp",
            AIFixtures.block("blanket", List.of()), // empty = covers everything
            AIFixtures.meleeAttack("punch", 20, 10));
        assertFalse(OpponentIntel.forOpponent(blanketBlocker).blocksPhysicalOnly);
    }

    @Test
    void guardBreakAndIntangibleFlagsComeFromAuthoredAttacks() {
        BattleCombatant opp = AIFixtures.sorcerer("opp",
            AIFixtures.guardBreakAttack("gb", 20, 10),
            AIFixtures.intangibleAttack("int", 20, 10));

        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        assertTrue(intel.hasGuardBreak);
        assertTrue(intel.hasIntangible);
    }

    @Test
    void countsCommittedDefensesFromTheLockedTimeline() {
        Move meleeDodge = AIFixtures.dodge("mDodge", "MELEE");
        Move rangedDodge = AIFixtures.dodge("rDodge", "RANGED");
        Move bothDodge = AIFixtures.dodge("bDodge", "BOTH");
        Move blk = AIFixtures.block("blk", List.of("PHYSICAL"));
        BattleCombatant opp = AIFixtures.sorcerer("opp", meleeDodge, rangedDodge, bothDodge, blk);
        AIFixtures.commitTimeline(opp, 60, meleeDodge, rangedDodge, bothDodge, blk);

        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        assertEquals(2, intel.committedMeleeDodge, "MELEE + BOTH");
        assertEquals(2, intel.committedRangedDodge, "RANGED + BOTH");
        assertEquals(1, intel.committedBlock);
    }

    @Test
    void recordsCommittedAttackFireTicks() {
        Move attack = AIFixtures.meleeAttack("punch", 20, 10); // apCost 10, unleash 1
        BattleCombatant opp = AIFixtures.sorcerer("opp", attack);
        AIFixtures.commitTimeline(opp, 60, attack); // placed at tick 1 => fire tick 1

        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        assertEquals(1, intel.committedAttackFireTicks.size());
        assertEquals(1, intel.committedAttackFireTicks.get(0));
    }

    @Test
    void emptyIntelForNullOrCharacterlessOpponent() {
        assertEquals(0, OpponentIntel.forOpponent(null).attacks.size());
        assertEquals(0, OpponentIntel.forOpponent(null).committedBlock);
        assertFalse(OpponentIntel.forOpponent(null).physicalOnly);
    }
}
