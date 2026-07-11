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
 * This is the only class that the application entry point (GraphicsMain)
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
        BattleCombatant player = new BattleCombatant(playerCharacter, playerCharacter.getAbilities());
        BattleCombatant enemy  = new BattleCombatant(enemyCharacter, enemyCharacter.getAbilities());
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

        // --- Player plan (two-board timeline UI) ---
        BattlePlan playerPlan = view.promptBattlePlan(player, enemy);
        player.setPlan(playerPlan);
        // Stopgap: expose the plan as the legacy single-timeline view so the
        // current CombatResolver (single-ticker) keeps running while the
        // cross-board execution refactor is pending.
        player.setTimeline(playerPlan == null ? new Timeline() : playerPlan.toLegacyTimeline());

        // --- Enemy AI plan ---
        BattlePlan enemyPlan = buildAiPlan(enemy, player);
        enemy.setPlan(enemyPlan);
        enemy.setTimeline(enemyPlan.toLegacyTimeline());
    }

    /**
     * Build the enemy's plan from the AI's move selection: place each move at
     * the first free slot on its assigned board, honouring AP/CE budgets.
     * Ability-locked and unaffordable moves are skipped.
     */
    private BattlePlan buildAiPlan(BattleCombatant ai, BattleCombatant opponent) {
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe());
        List<Move> moves = aiStrategy.selectMoves(ai, opponent);
        for (Move move : moves) {
            if (isLockedByAbility(ai, move)) {
                view.displayMessage(ai.getCharacter().getName() + "'s "
                    + move.getName() + " is locked by an ability — skipped.");
                continue;
            }
            int cost = CeEfficiencyCalculator.computeActualCost(
                move, ai.getEffectiveStats().getCursedEnergyEfficiency(), ai.getAbilityFlags()
            );
            ActionSegment segment = plan.placeFirstFit(move, cost);
            if (segment == null) {
                view.displayMessage(ai.getCharacter().getName()
                    + " could not place " + move.getName() + " — skipped.");
            }
        }
        return plan;
    }

    private boolean isLockedByAbility(BattleCombatant combatant, Move move) {
        return combatant.getAbilityFlags().lockedMoveTags.stream().anyMatch(move::hasTag);
    }

    // -------------------------------------------------------------------------
    // Resolution phase
    // -------------------------------------------------------------------------

    private void runResolutionPhase(BattleState state, BattleCombatant player, BattleCombatant enemy) {
        state.transitionTo(BattleState.Phase.RESOLUTION);

        // Drive the engine tick by tick so the view's pacing reflects real
        // progression, not a replay of pre-computed results. Each tick's events
        // are handed to the view as they are produced.
        List<CombatEvent> opening = resolver.beginResolution(state);
        if (!opening.isEmpty()) view.displayCombatEvents(opening, state);

        while (resolver.hasMoreTicks()) {
            List<CombatEvent> tickEvents = resolver.resolveTick(state);
            if (!tickEvents.isEmpty()) view.displayCombatEvents(tickEvents, state);
            if (state.isBattleOver()) break;
        }

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
        if (!state.isBattleOver()) view.awaitNextRound(state);
    }

}
