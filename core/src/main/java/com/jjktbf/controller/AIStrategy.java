package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;

import java.util.Random;

/**
 * Strategy interface for enemy AI round planning.
 *
 * <p>Each implementation decides which moves the AI commits to a round and
 * <em>where</em> on the AP timeline those moves are placed. The
 * {@link BattleController} calls {@link #selectPlan} during the planning phase
 * and runs whatever plan this returns.
 *
 * <p>Implementations receive the full AI combatant and the opponent, so they
 * can read HP, CE, effective stats, known moves, etc. The supplied {@link Random}
 * should be the source of any randomness so the battle's RNG stays centralised.
 *
 * <p>Adding a new AI difficulty or behaviour only requires implementing this
 * interface — the controller does not need to change.
 */
public interface AIStrategy {

    /**
     * Build the AI's complete round plan: which moves to commit and where to
     * place them across the offensive and defensive timelines.
     *
     * @param ai        the AI-controlled combatant
     * @param opponent  the opposing combatant (read-only context)
     * @param rng       shared battle RNG — the source of any randomness
     * @return          the finished plan (may be empty if the AI banks the round)
     */
    BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, Random rng);
}
