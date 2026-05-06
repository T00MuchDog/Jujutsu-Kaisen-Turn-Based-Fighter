package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;

import java.util.List;

/**
 * Strategy interface for enemy AI move selection.
 *
 * Each implementation decides which moves the AI queues each round.
 * The BattleController calls selectMoves() during the planning phase
 * and queues whatever this returns.
 *
 * Implementations receive the full AI combatant and the opponent, so
 * they can read HP, CE, effective stats, known moves, etc.
 *
 * Adding a new AI difficulty or behaviour only requires implementing this
 * interface — the controller does not need to change.
 */
public interface AIStrategy {

    /**
     * Select moves for the AI combatant to queue this round.
     *
     * @param ai        the AI-controlled combatant
     * @param opponent  the opposing combatant (read-only context)
     * @return ordered list of moves the AI wishes to queue (may be empty)
     */
    List<Move> selectMoves(BattleCombatant ai, BattleCombatant opponent);
}
