package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassiveSorcererAIStrategyTest {

    private final PassiveSorcererAIStrategy strategy = new PassiveSorcererAIStrategy();

    @Test
    void commitsMostlyToDefense() {
        // Three physical blocks (15 AP each) + one attack. The ~2/3 defense
        // reserve (40 AP of a 60 AP bar) is filled by defenses before attacks.
        Move b1 = AIFixtures.block("b1", List.of("PHYSICAL"), 1, 50, 15);
        Move b2 = AIFixtures.block("b2", List.of("PHYSICAL"), 1, 50, 15);
        Move b3 = AIFixtures.block("b3", List.of("PHYSICAL"), 1, 50, 15);
        Move attack = AIFixtures.meleeAttack("atk", 20, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai", b1, b2, b3, attack);
        BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));

        BattlePlan plan = strategy.selectPlan(ai, opp, new SeededRandomSource(1L));

        int defAp = apUsed(plan.defensiveTimeline().getSegments());
        int atkAp = apUsed(plan.offensiveTimeline().getSegments());

        assertTrue(defAp > atkAp, "passive spends more AP on defense than attack");
        assertTrue(defAp >= 36, "defense commits ~2/3 of the 60 AP bar: defAp=" + defAp);
    }

    @Test
    void bestDefenseGoesToTheStart() {
        Move strong = AIFixtures.block("strong", List.of("PHYSICAL"), 1, 90, 15);
        Move weak = AIFixtures.block("weak", List.of("PHYSICAL"), 1, 10, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai", strong, weak);
        BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));

        BattlePlan plan = strategy.selectPlan(ai, opp, new SeededRandomSource(1L));

        ActionSegment earliest = plan.defensiveTimeline().getSegments().stream()
            .min(Comparator.comparingInt(ActionSegment::getStartTick))
            .orElseThrow();
        assertEquals(90, earliest.getMove().getBlockDamageReduction(),
            "the highest-value defense is placed at the start of the round");
    }

    @Test
    void prefersPhysicalAttacksOverCursedEnergy() {
        // Passive attack weight has no power factor, so the physical preference
        // (PHYSICAL_WEIGHT 1.0 vs CE_WEIGHT 0.4) directly decides the weight.
        Move physical = AIFixtures.meleeAttack("phy", 20, 15);
        Move ce = AIFixtures.ceAttack("ce", 20, 15);
        OpponentIntel intel = OpponentIntel.forOpponent(
            AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10)));

        double phyWeight = PassiveSorcererAIStrategy.attackWeight(physical, intel);
        double ceWeight = PassiveSorcererAIStrategy.attackWeight(ce, intel);

        assertTrue(phyWeight > ceWeight,
            "passive weaves physical attacks to conserve CE: phy=" + phyWeight + " ce=" + ceWeight);
    }

    @Test
    void firstAttackIsPlacedAtTheStart() {
        Move attack = AIFixtures.meleeAttack("atk", 20, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai", AIFixtures.block("blk", List.of("PHYSICAL")), attack);
        BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));

        BattlePlan plan = strategy.selectPlan(ai, opp, new SeededRandomSource(3L));

        int earliestAttackStart = plan.offensiveTimeline().getSegments().stream()
            .mapToInt(ActionSegment::getStartTick)
            .min().orElseThrow();
        assertEquals(1, earliestAttackStart, "the first attack is placed at the start of the round");
    }

    private static int apUsed(List<ActionSegment> segments) {
        return segments.stream().mapToInt(s -> s.getMove().getApCost()).sum();
    }
}
