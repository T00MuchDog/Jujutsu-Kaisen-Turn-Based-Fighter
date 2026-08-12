package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartAIScoringTest {

    @Test
    void blockUsefulnessIsZeroWhenItCoversNoOpponentAttack() {
        BattleCombatant physicalOpp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("punch", 20, 10));
        OpponentIntel intel = OpponentIntel.forOpponent(physicalOpp);

        // A [CURSED_ENERGY]-only block cannot stop a purely physical attack.
        assertEquals(0.0, SmartAIScoring.blockUsefulness(
            AIFixtures.block("ceBlock", List.of("CURSED_ENERGY")), intel));
    }

    @Test
    void blockUsefulnessIsPositiveWhenItCoversAnOpponentAttack() {
        BattleCombatant physicalOpp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("punch", 20, 10));
        OpponentIntel intel = OpponentIntel.forOpponent(physicalOpp);

        assertTrue(SmartAIScoring.blockUsefulness(
            AIFixtures.block("phyBlock", List.of("PHYSICAL")), intel) > 0.0);
    }

    @Test
    void lowPotencyBlockIsRatedBelowOneThatCanContest() {
        Move potentAttack = AIFixtures.attack("heavy", 20, 10,
            java.util.Set.of(com.jjktbf.model.move.MoveTag.PHYSICAL,
                com.jjktbf.model.move.MoveTag.ATTACK, com.jjktbf.model.move.MoveTag.MELEE),
            false, 5); // potency 5
        BattleCombatant opp = AIFixtures.sorcerer("opp", potentAttack);
        OpponentIntel intel = OpponentIntel.forOpponent(opp);
        assertEquals(5, intel.maxAttackPotency);

        double weak = SmartAIScoring.blockUsefulness(
            AIFixtures.block("weak", List.of("PHYSICAL"), 1, 50), intel);
        double strong = SmartAIScoring.blockUsefulness(
            AIFixtures.block("strong", List.of("PHYSICAL"), 5, 50), intel);

        assertTrue(strong > weak, "a block that can contest the potency scores higher");
    }

    @Test
    void overReinforcedBlockIsDeWeightedVsAPhysicalOnlyOpponent() {
        BattleCombatant physicalOpp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("punch", 20, 10));
        OpponentIntel intel = OpponentIntel.forOpponent(physicalOpp);
        assertTrue(intel.physicalOnly);

        double minimal = SmartAIScoring.blockUsefulness(
            AIFixtures.block("phy", List.of("PHYSICAL")), intel);
        double reinforced = SmartAIScoring.blockUsefulness(
            AIFixtures.block("reinforced", List.of("PHYSICAL", "CURSED_ENERGY")), intel);

        assertTrue(minimal > reinforced,
            "vs a physical-only opponent, the minimal physical block is preferred over the reinforced one");
    }

    @Test
    void reinforcementAttackIsBoostedWhenOpponentBlocksArePhysicalOnly() {
        BattleCombatant physicalBlocker = AIFixtures.sorcerer("opp",
            AIFixtures.block("phy", List.of("PHYSICAL")),
            AIFixtures.meleeAttack("punch", 20, 10));
        OpponentIntel intel = OpponentIntel.forOpponent(physicalBlocker);
        assertTrue(intel.blocksPhysicalOnly);

        Move ceAttack = AIFixtures.ceAttack("ce", 20, 10);
        Move physical = AIFixtures.meleeAttack("phy", 20, 10);

        assertEquals(SmartAIScoring.REINFORCEMENT_BYPASS_BONUS,
            SmartAIScoring.reinforcementAttackMultiplier(ceAttack, intel));
        assertEquals(1.0,
            SmartAIScoring.reinforcementAttackMultiplier(physical, intel),
            "a physical attack gets no reinforcement bypass bonus");
    }

    @Test
    void reinforcementAttackIsNotBoostedWhenOpponentCanBlockCe() {
        BattleCombatant ceBlocker = AIFixtures.sorcerer("opp",
            AIFixtures.block("ce", List.of("PHYSICAL", "CURSED_ENERGY")),
            AIFixtures.meleeAttack("punch", 20, 10));
        OpponentIntel intel = OpponentIntel.forOpponent(ceBlocker);
        assertFalse(intel.blocksPhysicalOnly);

        assertEquals(1.0, SmartAIScoring.reinforcementAttackMultiplier(
            AIFixtures.ceAttack("ce", 20, 10), intel));
    }

    @Test
    void meleeAttackIsPenalisedByCommittedMeleeDodges() {
        Move meleeDodge = AIFixtures.dodge("mDodge", "MELEE");
        BattleCombatant opp = AIFixtures.sorcerer("opp", meleeDodge, AIFixtures.meleeAttack("p", 20, 10));
        AIFixtures.commitTimeline(opp, 60, meleeDodge);
        OpponentIntel intel = OpponentIntel.forOpponent(opp);
        assertEquals(1, intel.committedMeleeDodge);

        double meleeMult = SmartAIScoring.dodgeExposureMultiplier(AIFixtures.meleeAttack("m", 20, 10), intel);
        double rangedMult = SmartAIScoring.dodgeExposureMultiplier(AIFixtures.rangedAttack("r", 20, 10), intel);

        assertTrue(meleeMult < 1.0);
        assertTrue(rangedMult > meleeMult, "ranged is unaffected by a melee-only dodge");
    }

    @Test
    void reinforcementRequiresBothPhysicalAndCursedEnergy() {
        // "Reinforcement" = a PHYSICAL + CURSED_ENERGY strike, not any CE move.
        assertTrue(SmartAIScoring.isReinforcement(AIFixtures.ceAttack("rein", 20, 10)));
        assertFalse(SmartAIScoring.isReinforcement(AIFixtures.meleeAttack("phy", 20, 10)));
        assertFalse(SmartAIScoring.isReinforcement(AIFixtures.pureCeAttack("pureCe", 20, 10)));
    }

    @Test
    void effectMultiplierRewardsEffectRows() {
        assertEquals(SmartAIScoring.EFFECT_BONUS,
            SmartAIScoring.effectMultiplier(AIFixtures.meleeAttackWithEffect("e", 20, 10)));
        assertEquals(1.0,
            SmartAIScoring.effectMultiplier(AIFixtures.meleeAttack("plain", 20, 10)));
    }

    @Test
    void guardBreakOpponentDeValuesBlocks() {
        Move blk = AIFixtures.block("blk", List.of("PHYSICAL"));
        BattleCombatant gbOpp = AIFixtures.sorcerer("opp",
            AIFixtures.guardBreakAttack("gb", 20, 10));
        BattleCombatant plainOpp = AIFixtures.sorcerer("opp",
            AIFixtures.meleeAttack("p", 20, 10));

        double vsGuardBreak = SmartAIScoring.defenseValue(blk, OpponentIntel.forOpponent(gbOpp));
        double vsPlain = SmartAIScoring.defenseValue(blk, OpponentIntel.forOpponent(plainOpp));

        assertTrue(vsGuardBreak < vsPlain, "a block is worth less when the attacker can guard-break");
    }

    @Test
    void weightedRandomPickFollowsTheWeights() {
        Move a = AIFixtures.meleeAttack("A", 10, 5);
        Move b = AIFixtures.meleeAttack("B", 10, 5);
        int bCount = 0;
        SeededRandomSource rng = new SeededRandomSource(7L);
        for (int i = 0; i < 400; i++) {
            Move picked = SmartAIScoring.weightedRandomPick(List.of(a, b), List.of(1.0, 3.0), rng);
            if (picked == b) bCount++;
        }
        // B is weighted 3x; expect roughly 300/400. Allow a wide band to avoid flakiness.
        assertTrue(bCount > 240 && bCount < 360, "B should dominate but not always: " + bCount);
    }
}
