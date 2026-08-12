package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AggressiveSorcererAIStrategyTest {

    private final AggressiveSorcererAIStrategy strategy = new AggressiveSorcererAIStrategy();

    @Test
    void prefersCursedEnergyReinforcementOverPhysical() {
        // A CE-strong fighter: both categories compute competitive power, so the
        // archetype's CE>physical preference (CE_WEIGHT 1.0 vs PHYSICAL_WEIGHT 0.4)
        // is what decides the weight.
        Move ce = AIFixtures.ceAttack("ce", 20, 15);
        Move physical = AIFixtures.meleeAttack("phy", 20, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai", true,
            AIFixtures.ceStrongStats(), null, ce, physical);
        BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), 60);
        OpponentIntel intel = OpponentIntel.forOpponent(opp);

        double ceWeight = AggressiveSorcererAIStrategy.attackWeight(ce, ai, plan, intel, false);
        double phyWeight = AggressiveSorcererAIStrategy.attackWeight(physical, ai, plan, intel, false);

        assertTrue(ceWeight > phyWeight,
            "aggressive should weight CE/reinforcement above physical: ce=" + ceWeight + " phy=" + phyWeight);
    }

    @Test
    void isAttackHeavyAndRarelyDefends() {
        Move attack = AIFixtures.meleeAttack("atk", 20, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai",
            attack, AIFixtures.block("blk", List.of("PHYSICAL")), AIFixtures.dodge("dg", "BOTH"));
        BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));

        int attackTotal = 0;
        int defenseTotal = 0;
        for (long seed = 1; seed <= 30; seed++) {
            BattlePlan plan = strategy.selectPlan(ai, opp, new SeededRandomSource(seed));
            for (ActionSegment s : plan.allSegments()) {
                if (s.getMove().hasTag("ATTACK")) attackTotal++;
                else if (s.getMove().isDefensive()) defenseTotal++;
            }
        }
        assertTrue(attackTotal > defenseTotal * 2,
            "aggressive places far more attacks than defenses: atk=" + attackTotal + " def=" + defenseTotal);
    }

    @Test
    void bunchesPlacementsAtTheStartAndTheEnd() {
        // One attack type so every placement is the same move; a strong opponent
        // widens the grid so start/end clusters are clearly separated.
        Move attack = AIFixtures.meleeAttack("atk", 20, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai", attack);
        BattleCombatant strongOpp = AIFixtures.sorcerer("opp",
            true, AIFixtures.strongStats(), null, AIFixtures.meleeAttack("p", 20, 10));

        int minStart = Integer.MAX_VALUE;
        int maxStart = Integer.MIN_VALUE;
        for (long seed = 1; seed <= 10; seed++) {
            for (ActionSegment s : strategy.selectPlan(ai, strongOpp, new SeededRandomSource(seed)).allSegments()) {
                if (s.getMove().hasTag("ATTACK")) {
                    minStart = Math.min(minStart, s.getStartTick());
                    maxStart = Math.max(maxStart, s.getStartTick());
                }
            }
        }
        assertTrue(minStart <= 15, "some attacks are bunched at the start: minStart=" + minStart);
        assertTrue(maxStart >= 250, "some attacks are bunched at the end: maxStart=" + maxStart);
    }

    @Test
    void diversityRotatesTheArsenal() {
        Move a = AIFixtures.meleeAttack("A", 20, 15);
        Move b = AIFixtures.meleeAttack("B", 20, 15);
        BattleCombatant ai = AIFixtures.sorcerer("ai", a, b);
        BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));

        boolean usedA = false;
        boolean usedB = false;
        for (long seed = 1; seed <= 30; seed++) {
            Map<String, Integer> counts = placements(strategy, ai, opp, seed);
            if (counts.getOrDefault("A", 0) > 0) usedA = true;
            if (counts.getOrDefault("B", 0) > 0) usedB = true;
        }
        assertTrue(usedA && usedB, "diversity weighting should use both attacks across rounds");
    }

    private static Map<String, Integer> placements(
        AggressiveSorcererAIStrategy strategy, BattleCombatant ai, BattleCombatant opp, long seed
    ) {
        BattlePlan plan = strategy.selectPlan(ai, opp, new SeededRandomSource(seed));
        Map<String, Integer> counts = new HashMap<>();
        for (ActionSegment s : plan.allSegments()) {
            if (s.getMove().hasTag("ATTACK")) {
                counts.merge(s.getMove().getId(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
