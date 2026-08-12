package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing of {@link ArchetypeAIStrategy}: the hardcoded id map and the
 * stat-derived fallback for technique-less sorcerers, observed through the
 * character of the plan each archetype produces (attack-heavy vs defense-heavy).
 */
class ArchetypeAIStrategyTest {

    private final ArchetypeAIStrategy dispatcher = new ArchetypeAIStrategy();
    private final BattleCombatant opp = AIFixtures.sorcerer("opp", AIFixtures.meleeAttack("p", 20, 10));

    @Test
    void mappedAggressiveIdProducesAttackHeavyPlan() {
        // Yuji (000003) -> Aggressive.
        BattleCombatant ai = AIFixtures.sorcerer("000003",
            AIFixtures.meleeAttack("atk", 20, 15),
            AIFixtures.block("blk", List.of("PHYSICAL")));
        BattlePlan plan = dispatcher.selectPlan(ai, opp, new SeededRandomSource(1L));

        assertTrue(atkAp(plan) > defAp(plan), "aggressive spends more on attacks than defense");
    }

    @Test
    void mappedPassiveIdProducesDefenseHeavyPlan() {
        // Miwa (000002) -> Passive.
        BattleCombatant ai = AIFixtures.sorcerer("000002",
            AIFixtures.block("b1", List.of("PHYSICAL"), 1, 50, 15),
            AIFixtures.block("b2", List.of("PHYSICAL"), 1, 50, 15),
            AIFixtures.block("b3", List.of("PHYSICAL"), 1, 50, 15),
            AIFixtures.meleeAttack("atk", 20, 15));
        BattlePlan plan = dispatcher.selectPlan(ai, opp, new SeededRandomSource(1L));

        assertTrue(defAp(plan) > atkAp(plan), "passive spends more on defense than attacks");
    }

    @Test
    void unmappedOffenseStatsFallBackToAggressive() {
        BattleCombatant ai = AIFixtures.sorcerer("999991", true,
            AIFixtures.offenseStats(), null,
            AIFixtures.meleeAttack("atk", 20, 15),
            AIFixtures.block("blk", List.of("PHYSICAL")));
        BattlePlan plan = dispatcher.selectPlan(ai, opp, new SeededRandomSource(1L));

        assertTrue(atkAp(plan) > defAp(plan), "offense-leaning stats -> aggressive");
    }

    @Test
    void unmappedDefenseStatsFallBackToPassive() {
        BattleCombatant ai = AIFixtures.sorcerer("999992", true,
            AIFixtures.defenseStats(), null,
            AIFixtures.block("b1", List.of("PHYSICAL"), 1, 50, 15),
            AIFixtures.block("b2", List.of("PHYSICAL"), 1, 50, 15),
            AIFixtures.block("b3", List.of("PHYSICAL"), 1, 50, 15),
            AIFixtures.meleeAttack("atk", 20, 15));
        BattlePlan plan = dispatcher.selectPlan(ai, opp, new SeededRandomSource(1L));

        assertTrue(defAp(plan) > atkAp(plan), "defense-leaning stats -> passive");
    }

    @Test
    void techniqueSorcererRoutesToGreedyWithoutThrowing() {
        // A sorcerer WITH a cursed technique is handled by Greedy (until technique
        // archetypes exist) — it must still produce a valid plan.
        BattleCombatant ai = AIFixtures.sorcerer("000015", true,
            AIFixtures.baseStats(), "Cursed Speech",
            AIFixtures.meleeAttack("atk", 20, 15));
        BattlePlan plan = dispatcher.selectPlan(ai, opp, new SeededRandomSource(1L));

        assertFalse(plan.allSegments().isEmpty(), "greedy places at least the attack");
    }

    private static int atkAp(BattlePlan plan) {
        return plan.offensiveTimeline().getSegments().stream()
            .mapToInt(s -> s.getMove().getApCost()).sum();
    }

    private static int defAp(BattlePlan plan) {
        return plan.defensiveTimeline().getSegments().stream()
            .mapToInt(s -> s.getMove().getApCost()).sum();
    }
}
