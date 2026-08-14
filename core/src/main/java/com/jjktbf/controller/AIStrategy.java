package com.jjktbf.controller;

import com.jjktbf.model.character.coded.CursedSpeechAbility;
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
            java.util.List<Move> alreadyPlannedMoves = new java.util.ArrayList<>();
            for (com.jjktbf.model.combat.ActionSegment segment
                : new java.util.ArrayList<>(plan.allSegments())) {
                if (com.jjktbf.model.combat.MoveAvailability.restrictionReason(
                    state, ai, segment.getMove(), alreadyPlannedMoves) != null
                    || isAiUnsupported(segment.getMove())) {
                    plan.remove(segment);
                } else {
                    alreadyPlannedMoves.add(segment.getMove());
                }
            }
            if (plan.gridLength() != commonGridLength) {
                BattlePlan normalized = new BattlePlan(
                    plan.apBudget(), plan.ceBudget(), commonGridLength);
                for (com.jjktbf.model.combat.ActionSegment segment : plan.allSegments()) {
                    com.jjktbf.model.combat.ActionSegment normalizedSegment = normalized.place(
                        segment.getMove(), segment.getStartTick(), segment.getActualCeCost());
                    if (normalizedSegment == null) {
                        throw new IllegalArgumentException(
                            "AI plan cannot be normalized to the battle grid");
                    }
                    normalizedSegment.setTargets(segment.getTargets());
                }
                plan = normalized;
            }
            plan = SmartAIScoring.promoteGuaranteedKillOpening(state, ai, plan, rng);
            alreadyPlannedMoves.clear();
            for (com.jjktbf.model.combat.ActionSegment segment
                : new java.util.ArrayList<>(plan.allSegments())) {
                if (com.jjktbf.model.combat.MoveAvailability.restrictionReason(
                    state, ai, segment.getMove(), alreadyPlannedMoves) != null) {
                    plan.remove(segment);
                } else {
                    alreadyPlannedMoves.add(segment.getMove());
                }
            }
            assignExplicitTargets(state, plan, ai, rng);
            teamPlan.put(ai.getInstanceId(), plan);
        }
        return teamPlan;
    }

    /**
     * Ensure every hostile segment that requires selection has eligible explicit
     * targets, chosen deterministically under the shared seeded RNG.
     */
    default void assignExplicitTargets(
        BattleState state, BattlePlan plan, BattleCombatant ai, RandomSource rng
    ) {
        if (plan == null) return;
        List<BattleCombatant> enemies = state.activeEnemiesOf(ai);
        if (enemies.isEmpty()) return;
        for (com.jjktbf.model.combat.ActionSegment segment
            : new java.util.ArrayList<>(plan.allSegments())) {
            Move move = segment.getMove();
            MoveTargeting targeting = MoveTargeting.forMove(move);
            List<BattleCombatant> eligibleEnemies = enemies.stream()
                .filter(enemy -> CursedSpeechAbility.canTarget(move, enemy))
                .toList();
            if (eligibleEnemies.isEmpty()) {
                if (targeting == MoveTargeting.SINGLE_ENEMY
                    || targeting == MoveTargeting.MULTIPLE_ENEMIES) {
                    plan.remove(segment);
                }
                continue;
            }
            if (!segment.getTargets().isEmpty()) {
                if (CursedSpeechAbility.RETURN.equalsIgnoreCase(
                    CursedSpeechAbility.commandMode(move))) {
                    segment.setTargets(segment.getTargets().stream()
                        .map(state::combatant)
                        .filter(eligibleEnemies::contains)
                        .map(BattleCombatant::getInstanceId)
                        .distinct()
                        .limit(move.getAoeTargetCount())
                        .toList());
                }
                if (!segment.getTargets().isEmpty()) continue;
            }
            if (targeting == MoveTargeting.SINGLE_ENEMY) {
                BattleCombatant chosen = SmartAIScoring.weightedRandomTarget(
                    move, ai, eligibleEnemies, rng);
                segment.setTarget(chosen.getInstanceId());
            } else if (targeting == MoveTargeting.MULTIPLE_ENEMIES) {
                java.util.List<BattleCombatant> shuffled =
                    new java.util.ArrayList<>(eligibleEnemies);
                for (int i = shuffled.size() - 1; i > 0; i--) {
                    int swap = rng.nextInt(i + 1);
                    BattleCombatant value = shuffled.get(i);
                    shuffled.set(i, shuffled.get(swap));
                    shuffled.set(swap, value);
                }
                segment.setTargets(shuffled.stream()
                    .limit(Math.min(move.getAoeTargetCount(), shuffled.size()))
                    .map(BattleCombatant::getInstanceId)
                    .toList());
            }
        }
    }

    /** Compatibility overload for callers that still supply {@link Random}. */
    default BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, Random rng) {
        return selectPlan(ai, opponent, new SeededRandomSource(rng));
    }

    /**
     * Defensive moves that target an ally (any non-SELF {@link Move#getDefenseTargeting()
     * defense targeting}) are player-only for now: the AI does not yet aim them,
     * so it skips them entirely rather than place a segment it cannot resolve.
     * This keeps AI behaviour predictable and avoids the AI "wasting" such a move
     * by letting it fall back to self-protection.
     */
    static boolean isAiUnsupported(Move move) {
        return move != null
            && move.isDefensive()
            && move.getDefenseTargeting() != com.jjktbf.model.move.DefenseTargeting.SELF;
    }
}
