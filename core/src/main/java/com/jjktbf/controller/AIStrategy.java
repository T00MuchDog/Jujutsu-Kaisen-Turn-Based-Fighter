package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.MoveTargeting;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.Move;

import java.util.List;
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

    /**
     * Build one team's atomic round plan across every living AI-controlled
     * combatant. The default implementation plans each combatant independently
     * and assigns explicit targets for single-target moves (required: AI must
     * not leave a single-target segment without a selected target, which would
     * otherwise be deterministically retargeted at fire time).
     *
     * <p>Target selection is deterministic under the shared seeded RNG so the
     * same seed produces the same battle.
     *
     * @param state      the current battle state
     * @param aiTeam     the living AI-controlled combatants to plan
     * @param rng        shared battle source of authoritative randomness
     * @return           the atomic team plan (one entry per AI combatant)
     */
    default TeamBattlePlan selectTeamPlan(
        BattleState state, List<BattleCombatant> aiTeam, RandomSource rng
    ) {
        int commonGridLength = TeamBattlePlan.gridLengthForRound(state);
        TeamBattlePlan teamPlan = new TeamBattlePlan(
            aiTeam.isEmpty() ? null : aiTeam.get(0).getTeamId(),
            commonGridLength);
        for (BattleCombatant ai : aiTeam) {
            BattleCombatant opponent = state.firstActiveEnemyOf(ai);
            BattlePlan plan = selectPlan(ai, opponent, rng);
            if (plan.gridLength() != commonGridLength) {
                BattlePlan normalized = new BattlePlan(
                    plan.apBudget(), plan.ceBudget(), commonGridLength);
                for (com.jjktbf.model.combat.ActionSegment segment : plan.allSegments()) {
                    if (normalized.place(
                        segment.getMove(), segment.getStartTick(),
                        segment.getActualCeCost(), segment.getTarget()) == null) {
                        throw new IllegalArgumentException(
                            "AI plan cannot be normalized to the battle grid");
                    }
                }
                plan = normalized;
            }
            assignExplicitTargets(state, plan, ai, rng);
            teamPlan.put(ai.getInstanceId(), plan);
        }
        return teamPlan;
    }

    /**
     * Ensure every hostile single-target segment in {@code plan} has an explicit
     * selected target, chosen deterministically from the active enemies under the
     * shared seeded RNG. Without this, a single-target segment would rely on
     * fire-time retargeting; the spec requires the AI to assign targets itself.
     */
    default void assignExplicitTargets(
        BattleState state, BattlePlan plan, BattleCombatant ai, RandomSource rng
    ) {
        if (plan == null) return;
        List<BattleCombatant> enemies = state.activeEnemiesOf(ai);
        if (enemies.isEmpty()) return;
        for (com.jjktbf.model.combat.ActionSegment segment : plan.allSegments()) {
            if (segment.getTarget() != null) continue;
            Move move = segment.getMove();
            if (MoveTargeting.forMove(move).requiresSelectedTarget()) {
                // Deterministic pick under the shared seeded RNG.
                BattleCombatant chosen = enemies.get(rng.nextInt(enemies.size()));
                segment.setTarget(chosen.getInstanceId());
            }
        }
    }

    /** Compatibility overload for callers that still supply {@link Random}. */
    default BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, Random rng) {
        return selectPlan(ai, opponent, new SeededRandomSource(rng));
    }
}
