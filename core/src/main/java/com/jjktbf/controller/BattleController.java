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
 *
 * This is the only class that the application entry point (TextMain / GraphicsMain)
 * needs to instantiate and call to run a battle.
 */
public class BattleController {

    private final BattleView     view;
    private final CombatResolver resolver;

    public BattleController(BattleView view) {
        this.view     = view;
        this.resolver = new CombatResolver(new Random());
    }

    public BattleController(BattleView view, Random rng) {
        this.view     = view;
        this.resolver = new CombatResolver(rng);
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

        List<Move> enemyMoves = selectAiMoves(enemy, player);
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

    // -------------------------------------------------------------------------
    // Basic AI (placeholder — Strategy pattern ready)
    // -------------------------------------------------------------------------

    /**
     * Very simple greedy AI: fill AP bar with the highest-power moves available.
     * Replace this with a proper AIStrategy implementation when AI is designed.
     */
    private List<Move> selectAiMoves(BattleCombatant ai, BattleCombatant opponent) {
        List<Move> selected  = new java.util.ArrayList<>();
        int        remainAp  = ai.getEffectiveCombatStats().getMaxApBar();
        int        currentCe = ai.getCurrentCe();

        // Sort moves by base power descending, then pick greedily
        List<Move> sorted = new java.util.ArrayList<>(ai.getCharacter().getKnownMoves());
        sorted.sort((a, b) -> b.getBasePower() - a.getBasePower());

        for (Move move : sorted) {
            if (move.getApCost() > remainAp) continue;

            int ceCost = CeEfficiencyCalculator.computeActualCost(
                move, ai.getEffectiveStats().getCursedEnergyEfficiency()
            );
            if (ceCost > currentCe) continue;

            selected.add(move);
            remainAp  -= move.getApCost();
            currentCe -= ceCost;

            if (remainAp <= 0) break;
        }

        return selected;
    }
}
