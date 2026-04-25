package com.jjktbf.view;

import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * The MVC View interface for the battle screen.
 *
 * The core module depends ONLY on this interface — never on any implementation.
 * Swapping TextBattleView for PixelBattleView (graphics transition) requires
 * zero changes to any core class.
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
     * Prompt the player to select moves for their timeline.
     * Returns the list of moves in the order the player wants to queue them.
     * The view is responsible for validating AP budget and showing move costs.
     *
     * @param combatant   the player's combatant (for AP bar size, CE, known moves)
     * @param opponent    the opponent (for displaying their info during planning)
     * @return            ordered list of moves to queue; may be empty if player banks AP
     */
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
     * Display the battle over screen.
     * @param winner  the winning combatant, or null if a draw
     */
    void displayBattleOver(BattleCombatant winner, BattleState state);

    /**
     * Display a generic message (used for system-level info, errors, etc.)
     */
    void displayMessage(String message);
}
