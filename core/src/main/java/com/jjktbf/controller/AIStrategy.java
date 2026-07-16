package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.SeededRandomSource;

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
 * can read HP, CE, effective stats, known moves, etc. The supplied
 * {@link RandomSource} should be the source of any randomness so the battle's
 * sequence stays centralised and reproducible.
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
     * @param rng       shared battle source of authoritative randomness
     * @return          the finished plan (may be empty if the AI banks the round)
     */
    BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng);

    /** Compatibility overload for callers that still supply {@link Random}. */
    default BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, Random rng) {
        return selectPlan(ai, opponent, new SeededRandomSource(rng));
    }
}
