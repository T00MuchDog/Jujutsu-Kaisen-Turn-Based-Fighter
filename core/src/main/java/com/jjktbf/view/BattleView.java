package com.jjktbf.view;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.combat.CombatEvent;

import java.util.List;

/**
 * The MVC View interface for the battle screen.
 *
 * The core module depends ONLY on this interface — never on any implementation.
 * Swapping renderers (e.g. BattleScreen for a future one) requires zero
 * changes to any core class.
 *
 * All methods are called by the BattleController at appropriate points in the
 * battle loop.
 */
public interface BattleView {

    /**
     * Show the initial battlefield and hold before round-one planning begins.
     * Implementations with no interactive presentation may return immediately.
     */
    default void awaitBattleStart(BattleState state) {}

    /**
     * Display the full battle state at the start of a round (planning phase).
     * Shows HP, CE, BFS status, and available AP for both combatants.
     */
    void displayRoundStart(BattleState state);

    /**
     * Prompt the player to build their round plan via the two-board timeline UI
     * (offensive + defensive, drag-place, shared AP/CE budgets). The view owns
     * the entire drag-place interaction and returns the finished plan when the
     * player clicks "Lock In".
     *
     * <p>This is the blocking planning call: the controller thread spins until
     * the view signals confirmation.
     *
     * @param combatant   the player's combatant (for budgets, CE, known moves)
     * @param opponent    the opponent (for display only — never reveals their plan)
     * @return            the finished {@link BattlePlan}; may be empty (bank the round)
     */
    BattlePlan promptBattlePlan(BattleCombatant combatant, BattleCombatant opponent);

    /**
     * Prompt for one team's complete round plan atomically: one {@link BattlePlan}
     * per living controlled combatant, keyed by actor instance id. The view owns
     * per-page editing and must lock every living controlled page before
     * returning. This is the blocking multi-combatant planning call; the legacy
     * {@link #promptBattlePlan} 1v1 call remains for single-combatant battles.
     *
     * @param controlled  the living controlled combatants on the viewer's team
     *                    (initial fighters in roster order, then summons in
     *                    creation order)
     * @param state       the current battle state
     * @return            the atomic team plan (one entry per controlled combatant)
     */
    default TeamBattlePlan promptTeamBattlePlan(List<BattleCombatant> controlled, BattleState state) {
        // Default: plan each controlled combatant independently via the legacy
        // single-combatant prompt so existing 1v1 views keep working for
        // multi-combatant battles without changes.
        BattleTeamId teamId = controlled.isEmpty() ? null : controlled.get(0).getTeamId();
        TeamBattlePlan teamPlan = new TeamBattlePlan(teamId,
            com.jjktbf.model.combat.TeamBattlePlan.gridLengthForRound(state));
        for (BattleCombatant c : controlled) {
            BattleCombatant opponent = state.firstActiveEnemyOf(c);
            BattlePlan plan = promptBattlePlan(c, opponent);
            teamPlan.put(c.getInstanceId(), plan);
        }
        return teamPlan;
    }

    /**
     * Display a sequence of combat events that occurred during resolution.
     * The view may render these one by one with pauses, or all at once.
     *
     * @param events  ordered list of events from CombatResolver
     * @param state   current battle state (for live HP/CE values)
     */
    void displayCombatEvents(List<CombatEvent> events, BattleState state);

    /**
     * Enter round playback before the first resolution tick is processed. This
     * also fires for an actionless round, where no displayResolutionTick call
     * follows.
     */
    default void displayResolutionStart(BattleState state) {}

    /**
     * Display an AP tick that contains at least one active action segment. Idle
     * ticks are resolved internally but skipped by playback.
     */
    default void displayResolutionTick(int tick, BattleState state) {}

    /**
     * Display the end-of-round summary (status effects, remaining resources).
     */
    void displayRoundEnd(BattleState state);

    /**
     * Hold the round-end view until the player explicitly starts the next round.
     * This is a blocking call on the controller thread, like plan confirmation.
     */
    void awaitNextRound(BattleState state);

    /**
     * Display the battle over screen.
     * @param winner  the winning combatant, or null if a draw
     */
    void displayBattleOver(BattleCombatant winner, BattleState state);

    /**
     * Display a generic message (used for system-level info, errors, etc.)
     */
    void displayMessage(String message);

    /**
     * Whether the player has asked to leave the battle early (e.g. pressed
     * Escape). The {@link com.jjktbf.controller.BattleController} polls this
     * between phases so an abort unwinds the loop instead of running to a
     * knockout, and skips the battle-over screen since the player has already
     * navigated away.
     *
     * <p>Defaulted to {@code false} so views that never abort need no change.
     * Implementations backing this must be safe to read from the controller
     * (battle) thread while the flag is set on the render thread — a volatile
     * boolean is the usual choice.
     *
     * @return {@code true} once an abort has been requested
     */
    default boolean isAborted() { return false; }
}
