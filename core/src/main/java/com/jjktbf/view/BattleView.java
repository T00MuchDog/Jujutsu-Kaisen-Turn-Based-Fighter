package com.jjktbf.view;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;

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
     * Prompt the player to select moves for their timeline.
     * Returns the list of moves in the order the player wants to queue them.
     * The view is responsible for validating AP budget and showing move costs.
     *
     * @param combatant   the player's combatant (for AP bar size, CE, known moves)
     * @param opponent    the opponent (for displaying their info during planning)
     * @return            ordered list of moves to queue; may be empty if player banks AP
     * @deprecated superseded by {@link #promptBattlePlan}; retained temporarily
     *             so legacy views keep compiling during the planning-UI migration.
     */
    @Deprecated
    List<Move> promptMoveSelection(BattleCombatant combatant, BattleCombatant opponent);

    /**
     * Display a sequence of combat events that occurred during resolution.
     * The view may render these one by one with pauses, or all at once.
     *
     * @param events  ordered list of events from CombatResolver
     * @param state   current battle state (for live HP/CE values)
     */
    void displayCombatEvents(List<CombatEvent> events, BattleState state);

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
