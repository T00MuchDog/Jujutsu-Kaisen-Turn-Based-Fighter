package com.jjktbf;

import com.jjktbf.controller.BattleController;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.view.BattleView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleControllerExecutionTest {

    @Test
    void skipsIdleTicksBetweenLockedActionSegments() {
        Move setup = new Move.Builder("SETUP")
            .name("Setup")
            .category(MoveCategory.UTILITY)
            .apCost(1)
            .unleashPoint(1)
            .build();
        Move finisher = new Move.Builder("FINISHER")
            .name("Finisher")
            .category(MoveCategory.PHYSICAL)
            .basePower(100_000)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();

        CharacterStats stats = new CharacterStats.Builder().speed(80).build();
        Character player = new SorcererCharacter(
            "PLAYER", "Player", stats, null, List.of(setup, finisher));
        Character enemy = new SorcererCharacter("ENEMY", "Enemy", stats, null, List.of());
        BattlePlan plan = new BattlePlan(2, 0);
        assertNotNull(plan.place(setup, 1, 0));
        assertNotNull(plan.place(finisher, 10, 0));

        RecordingView view = new RecordingView(plan);
        BattleController controller = new BattleController(
            view,
            new SeededRandomSource(1L),
            (ai, opponent, rng) -> new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe())
        );

        controller.runBattle(player, enemy);

        assertEquals(List.of(1, 10), view.resolutionTicks);
        assertTrue(view.battleOverShown);
    }

    private static final class RecordingView implements BattleView {
        private final BattlePlan plan;
        private final List<Integer> resolutionTicks = new ArrayList<>();
        private boolean battleOverShown;

        private RecordingView(BattlePlan plan) {
            this.plan = plan;
        }

        @Override
        public void displayRoundStart(BattleState state) {
        }

        @Override
        public BattlePlan promptBattlePlan(BattleCombatant combatant, BattleCombatant opponent) {
            return plan;
        }

        @Override
        public void displayCombatEvents(List<CombatEvent> events, BattleState state) {
        }

        @Override
        public void displayResolutionTick(int tick, BattleState state) {
            resolutionTicks.add(tick);
        }

        @Override
        public void displayRoundEnd(BattleState state) {
        }

        @Override
        public void awaitNextRound(BattleState state) {
        }

        @Override
        public void displayBattleOver(BattleCombatant winner, BattleState state) {
            battleOverShown = true;
        }

        @Override
        public void displayMessage(String message) {
        }
    }
}
