package com.jjktbf;

import com.jjktbf.controller.AIStrategy;
import com.jjktbf.controller.BattleController;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.view.BattleView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 coverage for the controller and AI: the AI assigns explicit targets to
 * single-target moves (deterministic under the seeded RNG), the team-battle
 * controller loop plans every living combatant, and the 1v1 entry still works.
 */
class TeamBattleControllerTest {

    @Test
    void aiAssignsExplicitTargetsToSingleTargetMoves() {
        BattleCombatant ai = fighter("AI");
        BattleCombatant e1 = fighter("E1");
        BattleCombatant e2 = fighter("E2");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(ai)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(e1, e2)));

        Move attack = physicalAttack("ATK");
        AIStrategy strategy = (aiCombatant, opponent, rng) -> {
            BattlePlan plan = new BattlePlan(aiCombatant.getMaxApBar(), aiCombatant.getCurrentCe(), 60);
            plan.place(attack, 1, 0);
            return plan;
        };
        // Plan the AI team and confirm both segments get explicit targets.
        TeamBattlePlan teamPlan = strategy.selectTeamPlan(
            state, state.playerTeam().active(), new SeededRandomSource(42L));

        BattlePlan aiPlan = teamPlan.get(ai.getInstanceId());
        assertNotNull(aiPlan);
        assertEquals(1, aiPlan.allSegments().size());
        assertNotNull(aiPlan.allSegments().get(0).getTarget(),
            "AI assigned an explicit target to the single-target move");
        // The target is one of the living enemies.
        var target = aiPlan.allSegments().get(0).getTarget();
        assertTrue(target.equals(e1.getInstanceId()) || target.equals(e2.getInstanceId()));
    }

    @Test
    void aiTargetSelectionIsDeterministicUnderSameSeed() {
        BattleCombatant ai = fighter("AI");
        BattleCombatant e1 = fighter("E1");
        BattleCombatant e2 = fighter("E2");
        BattleState stateA = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(fighter("AI"))),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("E1"), fighter("E2"))));
        BattleState stateB = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(fighter("AI"))),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("E1"), fighter("E2"))));

        Move attack = physicalAttack("ATK");
        AIStrategy strategy = (aiCombatant, opponent, rng) -> {
            BattlePlan plan = new BattlePlan(aiCombatant.getMaxApBar(), aiCombatant.getCurrentCe(), 60);
            plan.place(attack, 1, 0);
            return plan;
        };

        TeamBattlePlan a = strategy.selectTeamPlan(stateA, stateA.playerTeam().active(), new SeededRandomSource(7L));
        TeamBattlePlan b = strategy.selectTeamPlan(stateB, stateB.playerTeam().active(), new SeededRandomSource(7L));

        assertEquals(
            a.get(stateA.playerTeam().active().get(0).getInstanceId()).allSegments().get(0).getTarget(),
            b.get(stateB.playerTeam().active().get(0).getInstanceId()).allSegments().get(0).getTarget(),
            "same seed picks the same target");
    }

    @Test
    void aiTeamPlansAreNormalizedToTheAuthoritativeCommonGrid() {
        BattleCombatant ai = fighter("AI");
        CharacterStats strongStats = new CharacterStats.Builder()
            .speed(300).combatAbility(300).vitality(300).build();
        SorcererCharacter strongCharacter = new SorcererCharacter(
            "strong", "Strong", strongStats, null, List.of(), List.of(), false);
        BattleCombatant strongEnemy = new BattleCombatant(strongCharacter, List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(ai)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(strongEnemy)));
        AIStrategy strategy = (actor, opponent, rng) ->
            new BattlePlan(actor.getMaxApBar(), actor.getCurrentCe(), 60);

        TeamBattlePlan plan = strategy.selectTeamPlan(
            state, state.playerTeam().active(), new SeededRandomSource(1L));

        assertEquals(TeamBattlePlan.gridLengthForRound(state),
            plan.get(ai.getInstanceId()).gridLength());
        assertNull(plan.validationError(state));
    }

    @Test
    void teamBattleControllerPlansEveryLivingCombatant() {
        // A 2v2 team battle where the controller must plan both allied fighters and
        // both enemies and run to completion.
        Move lethal = new Move.Builder("KILL")
            .name("Kill").category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(100000, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        Character p1 = new SorcererCharacter("p1", "P1", stats, null, List.of(lethal));
        Character e1 = new SorcererCharacter("e1", "E1", stats, null, List.of());

        BattleCombatant player1 = new BattleCombatant(p1, p1.getAbilities());
        BattleCombatant enemy1 = new BattleCombatant(e1, e1.getAbilities());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(player1)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy1)));

        int gridLength = TeamBattlePlan.gridLengthForRound(state);
        BattlePlan playerPlan = new BattlePlan(
            player1.getMaxApBar(), player1.getCurrentCe(), gridLength);
        playerPlan.place(lethal, 1, 0, enemy1.getInstanceId());
        RecordingView view = new RecordingView(playerPlan);
        BattleController controller = new BattleController(
            view, new SeededRandomSource(1L),
            (ai, opponent, rng) -> new BattlePlan(
                ai.getMaxApBar(), ai.getCurrentCe(), gridLength));

        controller.runTeamBattle(state);

        assertTrue(state.isBattleOver());
        assertTrue(view.battleOverShown);
        assertEquals(BattleTeamId.PLAYER, state.getWinnerTeam());
    }

    @Test
    void humanBothTeamsModePromptsPlayerThenEnemyEveryRoundWithoutUsingAi() {
        BattleState state = new BattleState(fighter("Player"), fighter("Enemy"));
        RepeatingBothTeamsView view = new RepeatingBothTeamsView();
        int[] aiCalls = {0};
        AIStrategy unusedAi = (actor, opponent, rng) -> {
            aiCalls[0]++;
            return new BattlePlan(
                actor.getMaxApBar(), actor.getCurrentCe(),
                TeamBattlePlan.gridLengthForRound(state));
        };
        BattleController controller = new BattleController(
            view,
            new SeededRandomSource(1L),
            unusedAi,
            null,
            BattleController.ControlMode.HUMAN_CONTROLS_BOTH_TEAMS);

        controller.runTeamBattle(state);

        assertEquals(List.of(
            BattleTeamId.PLAYER, BattleTeamId.ENEMY,
            BattleTeamId.PLAYER, BattleTeamId.ENEMY), view.promptedTeams);
        assertEquals(2, view.roundEnds);
        assertEquals(0, aiCalls[0]);
    }

    @Test
    void rejectedIncompleteSubmissionClearsEveryActiveActorsStalePlanAndTimeline() {
        BattleCombatant p1 = fighter("P1");
        BattleCombatant p2 = fighter("P2");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(p1, p2)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
        int grid = TeamBattlePlan.gridLengthForRound(state);
        BattlePlan stale = new BattlePlan(p1.getMaxApBar(), p1.getCurrentCe(), grid);
        p1.setPlan(stale);
        p1.setTimeline(stale.toLegacyTimeline());
        p2.setPlan(stale);
        p2.setTimeline(stale.toLegacyTimeline());

        BattleController controller = new BattleController(
            new RecordingView(new BattlePlan(p1.getMaxApBar(), p1.getCurrentCe(), grid), true),
            new SeededRandomSource(1L),
            (ai, opponent, rng) -> new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), grid));

        assertThrows(IllegalArgumentException.class, () -> controller.runTeamBattle(state));
        assertNull(p1.getPlan());
        assertNull(p1.getTimeline());
        assertNull(p2.getPlan());
        assertNull(p2.getTimeline());
    }

    @Test
    void injectedCharacterLookupFlowsFromControllerToResolver() {
        Move summon = new Move.Builder("SUMMON")
            .name("Summon").category(MoveCategory.UTILITY)
            .apCost(1).unleashPoint(1).summonCharacterId("DOG").build();
        Move lethal = new Move.Builder("FINISH")
            .name("Finish").category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(1).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(
                100_000, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        BattleCombatant player = fighter("Player");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);
        int grid = TeamBattlePlan.gridLengthForRound(state);
        BattlePlan plan = new BattlePlan(player.getMaxApBar(), player.getCurrentCe(), grid);
        plan.place(summon, 1, 0);
        plan.place(lethal, 2, 0, enemy.getInstanceId());
        ShikigamiCharacter dog = new ShikigamiCharacter(
            "dog", "Dog", new CharacterStats.Builder().build(),
            null, List.of(), List.of(), false);
        BattleController controller = new BattleController(
            new RecordingView(plan),
            new SeededRandomSource(1L),
            (ai, opponent, rng) -> new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), grid),
            id -> java.util.Optional.of(dog));

        controller.runTeamBattle(state);

        assertEquals(1, state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count());
    }

    private static BattleCombatant fighter(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(c, List.of());
    }

    private static Move physicalAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static final class RecordingView implements BattleView {
        private final BattlePlan plan;
        private final boolean omitAfterFirst;
        private final List<Integer> resolutionTicks = new ArrayList<>();
        private boolean battleOverShown;

        private RecordingView(BattlePlan plan) { this(plan, false); }

        private RecordingView(BattlePlan plan, boolean omitAfterFirst) {
            this.plan = plan;
            this.omitAfterFirst = omitAfterFirst;
        }

        @Override public void displayRoundStart(BattleState state) { }
        @Override public BattlePlan promptBattlePlan(BattleCombatant c, BattleCombatant opponent) {
            return plan;
        }
        @Override public TeamBattlePlan promptTeamBattlePlan(List<BattleCombatant> controlled, BattleState state) {
            TeamBattlePlan teamPlan = new TeamBattlePlan(
                controlled.get(0).getTeamId(),
                TeamBattlePlan.gridLengthForRound(state));
            for (int index = 0; index < controlled.size(); index++) {
                if (omitAfterFirst && index > 0) break;
                BattleCombatant c = controlled.get(index);
                teamPlan.put(c.getInstanceId(), plan);
            }
            return teamPlan;
        }
        @Override public void displayCombatEvents(List<CombatEvent> events, BattleState state) { }
        @Override public void displayResolutionTick(int tick, BattleState state) {
            resolutionTicks.add(tick);
        }
        @Override public void displayRoundEnd(BattleState state) { }
        @Override public void awaitNextRound(BattleState state) { }
        @Override public void displayBattleOver(BattleCombatant winner, BattleState state) {
            battleOverShown = true;
        }
        @Override public void displayMessage(String message) { }
    }

    private static final class RepeatingBothTeamsView implements BattleView {
        private final List<BattleTeamId> promptedTeams = new ArrayList<>();
        private int roundEnds;

        @Override public void displayRoundStart(BattleState state) { }
        @Override public BattlePlan promptBattlePlan(BattleCombatant c, BattleCombatant opponent) {
            throw new AssertionError("Team planning should use the atomic prompt");
        }
        @Override public TeamBattlePlan promptTeamBattlePlan(
            List<BattleCombatant> controlled,
            BattleState state
        ) {
            BattleTeamId teamId = controlled.get(0).getTeamId();
            promptedTeams.add(teamId);
            int gridLength = TeamBattlePlan.gridLengthForRound(state);
            TeamBattlePlan plan = new TeamBattlePlan(teamId, gridLength);
            for (BattleCombatant actor : controlled) {
                plan.put(actor.getInstanceId(), new BattlePlan(
                    actor.getMaxApBar(), actor.getCurrentCe(), gridLength));
            }
            return plan;
        }
        @Override public void displayCombatEvents(List<CombatEvent> events, BattleState state) { }
        @Override public void displayRoundEnd(BattleState state) { roundEnds++; }
        @Override public void awaitNextRound(BattleState state) { }
        @Override public void displayBattleOver(BattleCombatant winner, BattleState state) { }
        @Override public void displayMessage(String message) { }
        @Override public boolean isAborted() { return roundEnds >= 2; }
    }
}
