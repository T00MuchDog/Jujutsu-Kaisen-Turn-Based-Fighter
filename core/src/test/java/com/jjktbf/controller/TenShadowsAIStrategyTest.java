package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the Megumi (Ten Shadows) archetype: danger, summon decisions, round-1 behaviour, routing. */
class TenShadowsAIStrategyTest {

    private final TenShadowsAIStrategy strategy = new TenShadowsAIStrategy();
    private final List<Move> canonical = loadMoves();

    // --- dangerTier -----------------------------------------------------------

    @Test
    void dangerTierThresholds() {
        assertEquals(TenShadowsAIStrategy.DangerTier.LOW, TenShadowsAIStrategy.dangerTier(420));    // Haruta
        assertEquals(TenShadowsAIStrategy.DangerTier.MEDIUM, TenShadowsAIStrategy.dangerTier(500));  // Miwa
        assertEquals(TenShadowsAIStrategy.DangerTier.HIGH, TenShadowsAIStrategy.dangerTier(660));    // Yuji
    }

    // --- summonsToPlace -------------------------------------------------------

    @Test
    void openingSummonsByDanger() {
        // Round 1, empty field, cap 2: high danger opens with two, low danger with one.
        assertEquals(2, TenShadowsAIStrategy.summonsToPlace(1, 2, 0, 1.0, TenShadowsAIStrategy.DangerTier.HIGH));
        assertEquals(1, TenShadowsAIStrategy.summonsToPlace(1, 2, 0, 1.0, TenShadowsAIStrategy.DangerTier.LOW));
        // Cap-limited: a cap-1 Megumi still opens with at most one.
        assertEquals(1, TenShadowsAIStrategy.summonsToPlace(1, 1, 0, 1.0, TenShadowsAIStrategy.DangerTier.HIGH));
        // Already at cap: no summon.
        assertEquals(0, TenShadowsAIStrategy.summonsToPlace(1, 2, 2, 1.0, TenShadowsAIStrategy.DangerTier.HIGH));
    }

    @Test
    void laterRoundSummonsRequireCursedEnergy() {
        // After the opening, one paced summon per round — only above 35% CE.
        assertEquals(1, TenShadowsAIStrategy.summonsToPlace(2, 3, 1, 0.80, TenShadowsAIStrategy.DangerTier.HIGH));
        assertEquals(0, TenShadowsAIStrategy.summonsToPlace(2, 3, 1, 0.30, TenShadowsAIStrategy.DangerTier.HIGH),
            "no resummon below the 35% CE threshold");
        assertEquals(0, TenShadowsAIStrategy.summonsToPlace(3, 3, 3, 0.90, TenShadowsAIStrategy.DangerTier.HIGH),
            "no summon once the cap is reached");
    }

    // --- pickSummons ----------------------------------------------------------

    @Test
    void highDangerPicksStrongestAndLowDangerPicksCheapest() {
        // Fearsome-Womb-style menu: White Dog, Black Dog, Nue, Toad, Great Serpent.
        Move whiteDog = move("000030"), blackDog = move("000031"), nue = move("000035"),
            toad = move("000036"), serpent = move("000037");
        BattleCombatant megumi = AIFixtures.tenShadowsSorcerer("megumi",
            whiteDog, blackDog, nue, toad, serpent, move("000000"));
        BattleState state = state(megumi, AIFixtures.lowCeSorcererEnemy("e"));
        BattlePlan plan = new BattlePlan(megumi.getMaxApBar(), megumi.getCurrentCe(), 60);

        List<Move> high = TenShadowsAIStrategy.pickSummons(state, megumi, plan,
            List.of(whiteDog, blackDog, nue, toad, serpent), TenShadowsAIStrategy.DangerTier.HIGH, 1);
        assertEquals(1, high.size());
        assertEquals("000009", high.get(0).getSummonCharacterId(),
            "high danger picks Nue (strongest in this menu)");

        List<Move> low = TenShadowsAIStrategy.pickSummons(state, megumi, plan,
            List.of(whiteDog, blackDog, nue, toad, serpent), TenShadowsAIStrategy.DangerTier.LOW, 1);
        assertEquals(1, low.size());
        assertTrue(Set.of("000007", "000008").contains(low.get(0).getSummonCharacterId()),
            "low danger picks a cheap Divine Dog");
    }

    @Test
    void pickSummonsPicksTwoDistinctForHighDanger() {
        Move whiteDog = move("000030"), blackDog = move("000031"), nue = move("000035"),
            toad = move("000036"), serpent = move("000037");
        BattleCombatant megumi = AIFixtures.tenShadowsSorcerer("megumi",
            whiteDog, blackDog, nue, toad, serpent, move("000000"));
        BattleState state = state(megumi, AIFixtures.lowCeSorcererEnemy("e"));
        BattlePlan plan = new BattlePlan(megumi.getMaxApBar(), megumi.getCurrentCe(), 60);

        List<Move> picks = TenShadowsAIStrategy.pickSummons(state, megumi, plan,
            List.of(whiteDog, blackDog, nue, toad, serpent), TenShadowsAIStrategy.DangerTier.HIGH, 2);
        assertEquals(2, picks.size());
        assertEquals(2, picks.stream().map(Move::getSummonCharacterId).distinct().count(),
            "the two summons are distinct shikigami");
    }

    // --- End-to-end round 1 ---------------------------------------------------

    @Test
    void roundOneHighDangerSummonsTwoAndLowDangerSummonsOne() {
        BattleCombatant megumi = AIFixtures.tenShadowsSorcerer("megumi",
            move("000030"), move("000031"), move("000035"), move("000036"), move("000037"),
            move("000000"), move("000001"));

        BattleCombatant highEnemy = AIFixtures.cursedSpeechSorcerer("hi", move("000000")); // very high BST
        int high = countSummons(strategy.buildPlan(state(megumi, highEnemy), megumi, new SeededRandomSource(1L)));
        assertEquals(2, high, "high danger -> two opening summons");

        BattleCombatant lowEnemy = AIFixtures.lowCeSorcererEnemy("lo"); // low BST
        int low = countSummons(strategy.buildPlan(state(megumi, lowEnemy), megumi, new SeededRandomSource(1L)));
        assertEquals(1, low, "low danger -> one opening summon");
    }

    @Test
    void roundOneIncludesMixedOffenceAlongsideTheSummon() {
        BattleCombatant megumi = AIFixtures.tenShadowsSorcerer("megumi",
            move("000035"), move("000045"), move("000046"), move("000000"), move("000001"));
        BattleState state = state(megumi, AIFixtures.cursedSpeechSorcerer("hi", move("000000")));

        BattlePlan plan = strategy.buildPlan(state, megumi, new SeededRandomSource(1L));
        assertTrue(countSummons(plan) >= 1, "summons this round");
        assertTrue(plan.allSegments().stream().anyMatch(s ->
                s.getMove().hasTag("ATTACK") && !s.getMove().summonsCharacter()),
            "weaves at least one attack alongside the summon");
    }

    // --- Routing --------------------------------------------------------------

    @Test
    void dispatcherRoutesTenShadowsSorcerer() {
        ArchetypeAIStrategy dispatcher = new ArchetypeAIStrategy();
        BattleCombatant megumi = AIFixtures.tenShadowsSorcerer("000004",
            move("000030"), move("000035"), move("000000"));
        BattleState state = state(megumi, AIFixtures.lowCeSorcererEnemy("e"));

        TeamBattlePlan teamPlan = dispatcher.selectTeamPlan(
            state, state.playerTeam().active(), new SeededRandomSource(1L));
        BattlePlan plan = teamPlan.get(megumi.getInstanceId());

        assertTrue(plan.allSegments().stream().anyMatch(s -> s.getMove().summonsCharacter()),
            "a Ten Shadows sorcerer opens with a summon");
    }

    // --- helpers --------------------------------------------------------------

    private static int countSummons(BattlePlan plan) {
        return (int) plan.allSegments().stream().filter(s -> s.getMove().summonsCharacter()).count();
    }

    private Move move(String id) {
        return AIFixtures.canonicalMoveById(canonical, id);
    }

    private static BattleState state(BattleCombatant megumi, BattleCombatant enemy) {
        return new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(megumi)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
    }

    private static List<Move> loadMoves() {
        try {
            return AIFixtures.loadCanonicalMoves();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load canonical moves", e);
        }
    }
}
