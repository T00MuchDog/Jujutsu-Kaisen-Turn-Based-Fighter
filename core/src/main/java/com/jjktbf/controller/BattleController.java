package com.jjktbf.controller;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.combat.*;
import com.jjktbf.model.move.Move;
import com.jjktbf.view.BattleView;

import java.util.List;
import java.util.Random;

/**
 * Orchestrates the battle loop.
 *
 * The controller:
 *  - Holds references to the View (via interface) and the CombatResolver (model)
 *  - Drives the state machine: PLANNING → RESOLUTION → ROUND_END → repeat
 *  - Never performs rendering (that's the View's job)
 *  - Never contains damage math (that's CombatResolver's job)
 *  - Delegates AI move selection to an AIStrategy (default: GreedyAIStrategy)
 *
 * This is the only class that the application entry point (TextMain / GraphicsMain)
 * needs to instantiate and call to run a battle.
 */
public class BattleController {

    private final BattleView   view;
    private final CombatResolver resolver;
    private final AIStrategy   aiStrategy;

    public BattleController(BattleView view) {
        this(view, new Random(), new GreedyAIStrategy());
    }

    public BattleController(BattleView view, Random rng) {
        this(view, rng, new GreedyAIStrategy());
    }

    public BattleController(BattleView view, Random rng, AIStrategy aiStrategy) {
        this.view       = view;
        this.resolver   = new CombatResolver(rng);
        this.aiStrategy = aiStrategy;
    }

    /**
     * Run a complete battle between two characters.
     * Blocks until the battle is over.
     *
     * @param playerCharacter  the player's character
     * @param enemyCharacter   the opponent character
     */
    public void runBattle(Character playerCharacter, Character enemyCharacter) {
        BattleCombatant player = new BattleCombatant(playerCharacter);
        BattleCombatant enemy  = new BattleCombatant(enemyCharacter);
        BattleState     state  = new BattleState(player, enemy);

        view.displayMessage("=== BATTLE START: "
            + playerCharacter.getName() + " vs " + enemyCharacter.getName() + " ===");

        while (!state.isBattleOver()) {
            runPlanningPhase(state, player, enemy);
            if (state.isBattleOver()) break;

            runResolutionPhase(state, player, enemy);
            if (state.isBattleOver()) break;

            runRoundEndPhase(state, player, enemy);
        }

        view.displayBattleOver(state.getWinner(), state);
    }

    // -------------------------------------------------------------------------
    // Planning phase
    // -------------------------------------------------------------------------

    private void runPlanningPhase(BattleState state, BattleCombatant player, BattleCombatant enemy) {
        state.transitionTo(BattleState.Phase.PLANNING);
        view.displayRoundStart(state);

        // --- Player move selection ---
        int playerApBar = player.getEffectiveCombatStats().getMaxApBar();
        Timeline playerTimeline = new Timeline(playerApBar);
        player.setTimeline(playerTimeline);

        List<Move> playerMoves = view.promptMoveSelection(player, enemy);
        for (Move move : playerMoves) {
            int cost = CeEfficiencyCalculator.computeActualCost(
                move, player.getEffectiveStats().getCursedEnergyEfficiency()
            );
            MoveBlock block = playerTimeline.addMove(move, cost);
            if (block == null) {
                view.displayMessage("Not enough AP to queue " + move.getName() + " — skipped.");
            }
        }

        // --- Enemy AI move selection ---
        int enemyApBar = enemy.getEffectiveCombatStats().getMaxApBar();
        Timeline enemyTimeline = new Timeline(enemyApBar);
        enemy.setTimeline(enemyTimeline);

        List<Move> enemyMoves = aiStrategy.selectMoves(enemy, player);
        for (Move move : enemyMoves) {
            int cost = CeEfficiencyCalculator.computeActualCost(
                move, enemy.getEffectiveStats().getCursedEnergyEfficiency()
            );
            enemyTimeline.addMove(move, cost);
        }
    }

    // -------------------------------------------------------------------------
    // Resolution phase
    // -------------------------------------------------------------------------

    private void runResolutionPhase(BattleState state, BattleCombatant player, BattleCombatant enemy) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = resolver.resolveRound(state);
        view.displayCombatEvents(events, state);

        state.checkAndResolveBattleOver();
    }

    // -------------------------------------------------------------------------
    // Round end phase
    // -------------------------------------------------------------------------

    private void runRoundEndPhase(BattleState state, BattleCombatant player, BattleCombatant enemy) {
        state.transitionTo(BattleState.Phase.ROUND_END);
        List<CombatEvent> events = resolver.processRoundEnd(state);
        view.displayCombatEvents(events, state);
        view.displayRoundEnd(state);
    }

}
