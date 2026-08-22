package com.jjktbf.controller;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.combat.*;
import com.jjktbf.view.BattleView;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Orchestrates the battle loop.
 *
 * The controller:
 *  - Holds references to the View (via interface) and the CombatResolver (model)
 *  - Drives the state machine: PLANNING → RESOLUTION → ROUND_END → repeat
 *  - Never performs rendering (that's the View's job)
 *  - Never contains damage math (that's CombatResolver's job)
 *  - Delegates AI move selection to an AIStrategy (default: ArchetypeAIStrategy,
 *    which dispatches to a per-archetype brain — e.g. ShikigamiAIStrategy for
 *    shikigami, GreedyAIStrategy for everyone else)
 *
 * This is the only class that the application entry point (GraphicsMain)
 * needs to instantiate and call to run a battle.
 */
public class BattleController {

    public enum ControlMode {
        PLAYER_VS_AI,
        HUMAN_CONTROLS_BOTH_TEAMS
    }

    private final BattleView   view;
    private final CombatResolver resolver;
    private final AIStrategy   aiStrategy;
    private final RandomSource rng;
    private final ControlMode controlMode;

    public BattleController(BattleView view) {
        this(view, new SeededRandomSource(), new ArchetypeAIStrategy(), null,
            ControlMode.PLAYER_VS_AI);
    }

    public BattleController(BattleView view, BattleCharacterLookup characterLookup) {
        this(view, characterLookup, ControlMode.PLAYER_VS_AI);
    }

    public BattleController(
        BattleView view,
        BattleCharacterLookup characterLookup,
        ControlMode controlMode
    ) {
        this(view, new SeededRandomSource(), new ArchetypeAIStrategy(), characterLookup, controlMode);
    }

    /** Compatibility constructor for callers that still supply {@link Random}. */
    public BattleController(BattleView view, Random rng) {
        this(view, new SeededRandomSource(rng));
    }

    /** Compatibility constructor with an injected summon lookup. */
    public BattleController(
        BattleView view, Random rng, BattleCharacterLookup characterLookup
    ) {
        this(view, new SeededRandomSource(rng), new ArchetypeAIStrategy(), characterLookup,
            ControlMode.PLAYER_VS_AI);
    }

    public BattleController(BattleView view, RandomSource rng) {
        this(view, rng, new ArchetypeAIStrategy(), null);
    }

    public BattleController(
        BattleView view, RandomSource rng, BattleCharacterLookup characterLookup
    ) {
        this(view, rng, new ArchetypeAIStrategy(), characterLookup, ControlMode.PLAYER_VS_AI);
    }

    /** Compatibility constructor for callers that still supply {@link Random}. */
    public BattleController(BattleView view, Random rng, AIStrategy aiStrategy) {
        this(view, new SeededRandomSource(rng), aiStrategy);
    }

    /** Compatibility constructor with an injected summon lookup. */
    public BattleController(
        BattleView view,
        Random rng,
        AIStrategy aiStrategy,
        BattleCharacterLookup characterLookup
    ) {
        this(view, new SeededRandomSource(rng), aiStrategy, characterLookup,
            ControlMode.PLAYER_VS_AI);
    }

    public BattleController(BattleView view, RandomSource rng, AIStrategy aiStrategy) {
        this(view, rng, aiStrategy, null);
    }

    public BattleController(
        BattleView view,
        RandomSource rng,
        AIStrategy aiStrategy,
        BattleCharacterLookup characterLookup
    ) {
        this(view, rng, aiStrategy, characterLookup, ControlMode.PLAYER_VS_AI);
    }

    public BattleController(
        BattleView view,
        RandomSource rng,
        AIStrategy aiStrategy,
        BattleCharacterLookup characterLookup,
        ControlMode controlMode
    ) {
        this.view       = view;
        this.resolver   = new CombatResolver(rng, characterLookup);
        this.aiStrategy = aiStrategy;
        this.rng        = rng;
        this.controlMode = Objects.requireNonNull(controlMode, "controlMode");
    }

    /**
     * Run a complete battle between two characters (legacy 1v1 entry point).
     * Blocks until the battle is over. Equivalent to a two-team battle where
     * each team has exactly one fighter; preserved so existing 1v1 callers
     * compile and behave identically.
     */
    public void runBattle(Character playerCharacter, Character enemyCharacter) {
        runBattle(playerCharacter, enemyCharacter, BattleStatMode.STANDARD);
    }

    public void runBattle(
        Character playerCharacter,
        Character enemyCharacter,
        BattleStatMode statMode
    ) {
        BattleCombatant player = new BattleCombatant(
            playerCharacter, playerCharacter.getAbilities(), statMode);
        BattleCombatant enemy = new BattleCombatant(
            enemyCharacter, enemyCharacter.getAbilities(), statMode);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(com.jjktbf.model.combat.BattleTeamId.PLAYER, List.of(player)),
            BattleState.teamOfFighters(com.jjktbf.model.combat.BattleTeamId.ENEMY, List.of(enemy)));

        runTeamBattleLoop(state);
    }

    /**
     * Run a complete battle between two pre-built teams of combatants. Each team
     * may contain any number of fighters (2v2 and beyond). The view is asked for
     * one atomic team plan per round; AI combatants are planned with explicit
     * targets. Blocks until the battle is over.
     */
    public void runTeamBattle(BattleState state) {
        runTeamBattleLoop(state);
    }

    private void runTeamBattleLoop(BattleState state) {
        view.displayMessage("The battle between " + teamName(state.playerTeam())
            + " and " + teamName(state.enemyTeam()) + " begins.");
        view.awaitBattleStart(state);
        if (view.isAborted()) return;

        while (!state.isBattleOver()) {
            if (view.isAborted()) return;

            runPlanningPhase(state);
            if (state.isBattleOver() || view.isAborted()) break;

            runResolutionPhase(state);
            if (state.isBattleOver() || view.isAborted()) break;

            runRoundEndPhase(state);
        }

        if (view.isAborted()) return;
        view.displayBattleOver(state.getWinner(), state);
    }

    private static String teamName(BattleTeam team) {
        List<String> names = team.all().stream()
            .filter(BattleCombatant::isFighter)
            .map(combatant -> combatant.getCharacter().getName())
            .toList();
        if (names.size() < 2) return names.isEmpty() ? team.id().value() : names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1))
            + " and " + names.get(names.size() - 1);
    }

    // -------------------------------------------------------------------------
    // Planning phase
    // -------------------------------------------------------------------------

    private void runPlanningPhase(BattleState state) {
        state.transitionTo(BattleState.Phase.PLANNING);
        List<CombatEvent> abilityCostEvents = resolver.processRoundStart(state);
        if (!abilityCostEvents.isEmpty()) view.displayCombatEvents(abilityCostEvents, state);
        if (state.isBattleOver()) return;
        view.displayRoundStart(state);

        // --- Player team plan (atomic, one BattlePlan per controlled combatant) ---
        // The first fighter of the player team is the human-controlled side; for a
        // 1v1 this is exactly one combatant. Summoned units are controlled by the
        // same participant as their team, so they are planned here too once active.
        java.util.List<BattleCombatant> playerControlled = controlledFor(state, state.playerTeam().id());
        if (!playerControlled.isEmpty()) {
            com.jjktbf.model.combat.TeamBattlePlan playerTeamPlan =
                view.promptTeamBattlePlan(playerControlled, state);
            attachTeamPlan(state, state.playerTeam().id(), playerTeamPlan);
        }

        // --- Enemy team plan ---
        java.util.List<BattleCombatant> enemyControlled =
            controlledFor(state, state.enemyTeam().id());
        if (!enemyControlled.isEmpty()) {
            com.jjktbf.model.combat.TeamBattlePlan enemyTeamPlan =
                controlMode == ControlMode.HUMAN_CONTROLS_BOTH_TEAMS
                    ? view.promptTeamBattlePlan(enemyControlled, state)
                    : aiStrategy.selectTeamPlan(state, enemyControlled, rng);
            attachTeamPlan(state, state.enemyTeam().id(), enemyTeamPlan);
        }
    }

    /**
     * The living, controllable combatants on a team: active fighters and active
     * summons (summons join planning the round after they are created).
     */
    private static java.util.List<BattleCombatant> controlledFor(BattleState state, com.jjktbf.model.combat.BattleTeamId teamId) {
        com.jjktbf.model.combat.BattleTeam team = state.teamOf(teamId);
        if (team == null) return java.util.List.of();
        return team.active();
    }

    /** Validate and atomically attach one authoritative team submission. */
    private static void attachTeamPlan(
        BattleState state,
        BattleTeamId expectedTeamId,
        TeamBattlePlan teamPlan
    ) {
        BattleTeam team = state.teamOf(expectedTeamId);
        if (team == null) throw new IllegalArgumentException("Unknown team " + expectedTeamId);

        // Clear the complete active roster before reading the submission. Even a
        // malformed/omitted page can therefore never replay last round's actions.
        for (BattleCombatant actor : team.active()) {
            actor.setPlan(null);
            actor.setTimeline(null);
        }

        if (teamPlan == null) {
            throw new IllegalArgumentException("Team plan is required for " + expectedTeamId);
        }
        if (!expectedTeamId.equals(teamPlan.teamId())) {
            throw new IllegalArgumentException("Team plan belongs to " + teamPlan.teamId()
                + ", expected " + expectedTeamId);
        }
        String validationError = teamPlan.validationError(state);
        if (validationError != null) throw new IllegalArgumentException(validationError);

        for (BattleCombatant actor : team.active()) {
            BattlePlan plan = teamPlan.get(actor.getInstanceId());
            actor.setPlan(plan);
            actor.setTimeline(plan.toLegacyTimeline());
        }
    }

    // -------------------------------------------------------------------------
    // Resolution phase
    // -------------------------------------------------------------------------

    private void runResolutionPhase(BattleState state) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        view.displayResolutionStart(state);

        // Drive the engine tick by tick so the view's pacing reflects real
        // progression, not a replay of pre-computed results. Each tick's events
        // are handed to the view as they are produced.
        List<CombatEvent> opening = resolver.beginResolution(state);
        if (!opening.isEmpty()) view.displayCombatEvents(opening, state);

        while (resolver.hasMoreTicks()) {
            int tick = state.getCurrentTick();
            if (anyActiveActionAt(state, tick)) {
                view.displayResolutionTick(tick, state);
            }
            List<CombatEvent> tickEvents = resolver.resolveTick(state);
            if (!tickEvents.isEmpty()) view.displayCombatEvents(tickEvents, state);
            if (state.isBattleOver()) break;
        }

        state.checkAndResolveBattleOver();
    }

    private static boolean anyActiveActionAt(BattleState state, int tick) {
        for (BattleCombatant c : state.activeCombatants()) {
            Timeline timeline = c.getTimeline();
            if (timeline != null && timeline.hasResolutionAt(tick)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Round end phase
    // -------------------------------------------------------------------------

    private void runRoundEndPhase(BattleState state) {
        state.transitionTo(BattleState.Phase.ROUND_END);
        List<CombatEvent> events = resolver.processRoundEnd(state);
        view.displayCombatEvents(events, state);
        view.displayRoundEnd(state);
        if (!state.isBattleOver()) view.awaitNextRound(state);
    }

}
