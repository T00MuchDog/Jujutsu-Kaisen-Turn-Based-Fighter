package com.jjktbf.controller;

import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioural tests for the Inumaki (Cursed Speech) archetype. */
class CursedSpeechAIStrategyTest {

    private final CursedSpeechAIStrategy strategy = new CursedSpeechAIStrategy();
    private final List<Move> canonical = loadMoves();

    @Test
    void opensWithDontMoveInMajorityOfRounds() {
        BattleCombatant cs = cs(move("000065"), move("000068"), move("000000"), AIFixtures.dodge("dg", "BOTH"));
        BattleState state = state(cs, AIFixtures.lowCeSorcererEnemy("e"));
        int openedWithDontMove = 0;
        for (long seed = 1; seed <= 20; seed++) {
            BattlePlan plan = strategy.buildPlan(state, cs, new SeededRandomSource(seed));
            ActionSegment first = plan.allSegments().stream()
                .filter(s -> CursedSpeechPlanning.isCursedSpeech(s.getMove()))
                .min(Comparator.comparingInt(ActionSegment::getStartTick))
                .orElse(null);
            if (first != null
                && CursedSpeechAbility.DONT_MOVE.equals(CursedSpeechPlanning.commandMode(first.getMove()))) {
                openedWithDontMove++;
            }
        }
        assertTrue(openedWithDontMove >= 12,
            "opens with Don't Move in the majority of rounds: " + openedWithDontMove);
    }

    @Test
    void capsCursedSpeechMovesAtThree() {
        BattleCombatant cs = cs(move("000069"), move("000070"), move("000071"),
            move("000072"), move("000073"), move("000000"), AIFixtures.dodge("dg", "BOTH"));
        BattleState state = state(cs, AIFixtures.lowCeSorcererEnemy("e"));
        for (long seed = 1; seed <= 10; seed++) {
            BattlePlan plan = strategy.buildPlan(state, cs, new SeededRandomSource(seed));
            long csCount = plan.allSegments().stream()
                .filter(s -> CursedSpeechPlanning.isCursedSpeech(s.getMove())).count();
            assertTrue(csCount <= 3, "at most 3 Cursed Speech commands: " + csCount);
        }
    }

    @Test
    void damagingCommandsPreferSorcererTargets() {
        BattleCombatant cs = cs(move("000068"), move("000000"), AIFixtures.dodge("dg", "BOTH")); // Plummet only
        BattleCombatant sor = AIFixtures.lowCeSorcererEnemy("sor");
        BattleCombatant shi = AIFixtures.shikigamiEnemy("shi");
        BattleState state = state(cs, sor, shi);

        int plummetPlaced = 0;
        int plummetTargetedSorcerer = 0;
        for (long seed = 1; seed <= 20; seed++) {
            BattlePlan plan = strategy.buildPlan(state, cs, new SeededRandomSource(seed));
            for (ActionSegment s : plan.allSegments()) {
                if (CursedSpeechAbility.PLUMMET.equals(CursedSpeechPlanning.commandMode(s.getMove()))) {
                    plummetPlaced++;
                    if (s.getTargets().contains(sor.getInstanceId())) plummetTargetedSorcerer++;
                }
            }
        }
        assertTrue(plummetPlaced > 0);
        assertEquals(plummetPlaced, plummetTargetedSorcerer,
            "Plummet (damaging) always includes the sorcerer target");
    }

    @Test
    void statusCommandsPreferShikigamiTargets() {
        BattleCombatant cs = cs(move("000065"), move("000000"), AIFixtures.dodge("dg", "BOTH")); // Don't Move only
        BattleCombatant sor = AIFixtures.lowCeSorcererEnemy("sor");
        BattleCombatant shi = AIFixtures.shikigamiEnemy("shi");
        BattleState state = state(cs, sor, shi);

        int dontMovePlaced = 0;
        int dontMoveTargetedShikigami = 0;
        for (long seed = 1; seed <= 20; seed++) {
            BattlePlan plan = strategy.buildPlan(state, cs, new SeededRandomSource(seed));
            for (ActionSegment s : plan.allSegments()) {
                if (CursedSpeechAbility.DONT_MOVE.equals(CursedSpeechPlanning.commandMode(s.getMove()))) {
                    dontMovePlaced++;
                    if (s.getTargets().contains(shi.getInstanceId())) dontMoveTargetedShikigami++;
                }
            }
        }
        assertTrue(dontMovePlaced > 0);
        assertEquals(dontMovePlaced, dontMoveTargetedShikigami,
            "Don't Move (status) always includes the shikigami target");
    }

    @Test
    void multitargetRecoilStaysWithinBudgetAndIsNeverLethal() {
        BattleCombatant cs = cs(move("000069"), move("000072"), move("000000"), AIFixtures.dodge("dg", "BOTH"));
        BattleState state = state(cs,
            AIFixtures.lowCeSorcererEnemy("e1"), AIFixtures.lowCeSorcererEnemy("e2"),
            AIFixtures.shikigamiEnemy("e3"));
        int budget = (int) Math.round(0.20 * cs.getCurrentHp());

        for (long seed = 1; seed <= 10; seed++) {
            BattlePlan plan = strategy.buildPlan(state, cs, new SeededRandomSource(seed));
            int cumulativeCeCost = 0;
            for (ActionSegment s : plan.allSegments()) {
                if (!CursedSpeechPlanning.isCursedSpeech(s.getMove())) continue;
                Move m = s.getMove();
                int thisCe = cs.computeMoveCeCost(m);
                int userCePost = Math.max(0, cs.getCurrentCe() - cumulativeCeCost - thisCe);
                cumulativeCeCost += thisCe;

                List<Integer> recoils = new ArrayList<>();
                for (CombatantId tid : s.getTargets()) {
                    recoils.add(CursedSpeechPlanning.predictedRecoil(m, cs, state.combatant(tid), userCePost));
                }
                if (recoils.isEmpty()) continue;
                int min = recoils.stream().min(Integer::compare).orElse(0);
                int sum = recoils.stream().mapToInt(Integer::intValue).sum();
                int extra = sum - min; // recoil from multitarget (beyond the first target)
                assertTrue(extra <= budget,
                    "extra-target recoil within the 20% budget: extra=" + extra + " budget=" + budget);
                assertTrue(min < cs.getCurrentHp(), "the first target's recoil is never lethal");
            }
        }
    }

    @Test
    void lethalRecoilCommandIsNeverPlaced() {
        // Die (baseRecoil 100) against a high-CE target at low HP is lethal.
        BattleCombatant cs = cs(move("000076"), move("000069"), move("000000")); // Die, Don't Move, Basic Strike
        cs.applyDamage(cs.getMaxHp() - 10); // HP = 10
        BattleCombatant highCeEnemy = AIFixtures.cursedSpeechSorcerer("e", move("000000"));
        BattleState state = state(cs, highCeEnemy);

        boolean diePlaced = false;
        for (long seed = 1; seed <= 15; seed++) {
            BattlePlan plan = strategy.buildPlan(state, cs, new SeededRandomSource(seed));
            for (ActionSegment s : plan.allSegments()) {
                if (CursedSpeechAbility.DIE.equals(CursedSpeechPlanning.commandMode(s.getMove()))) {
                    diePlaced = true;
                }
            }
        }
        assertFalse(diePlaced, "a command whose recoil would kill him is never placed");
    }

    @Test
    void dispatcherRoutesCursedSpeechSorcererToCursedSpeechPlan() {
        ArchetypeAIStrategy dispatcher = new ArchetypeAIStrategy();
        BattleCombatant cs = cs("000015", move("000069"), move("000000"));
        BattleState state = state(cs, AIFixtures.lowCeSorcererEnemy("e"));

        TeamBattlePlan teamPlan = dispatcher.selectTeamPlan(
            state, state.playerTeam().active(), new SeededRandomSource(1L));
        BattlePlan plan = teamPlan.get(cs.getInstanceId());

        assertTrue(plan.allSegments().stream()
                .anyMatch(s -> CursedSpeechPlanning.isCursedSpeech(s.getMove())),
            "a Cursed Speech sorcerer is planned with the Cursed Speech archetype");
    }

    // --- helpers ----------------------------------------------------------------

    private BattleCombatant cs(Move... moves) {
        return cs("cs", moves);
    }

    private BattleCombatant cs(String id, Move... moves) {
        return AIFixtures.cursedSpeechSorcerer(id, moves);
    }

    private static BattleState state(BattleCombatant cs, BattleCombatant... enemies) {
        return new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(cs)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemies)));
    }

    private Move move(String id) {
        return AIFixtures.canonicalMoveById(canonical, id);
    }

    private static List<Move> loadMoves() {
        try {
            return AIFixtures.loadCanonicalMoves();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load canonical moves", e);
        }
    }
}
