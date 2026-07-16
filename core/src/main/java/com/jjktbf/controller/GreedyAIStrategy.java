package com.jjktbf.controller;

import com.jjktbf.model.character.AbilityApplicator.AbilityFlags;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Greedy, lightly-randomised AI round planner.
 *
 * <p><b>Selection.</b> A single greedy loop keeps committing moves until none of
 * the AI's known moves is affordable (AP and CE) or placeable. The same move may
 * be committed multiple times in a round, exactly as a human can, for as long as
 * the budgets allow. Each iteration the AI picks the next move with a
 * weighted-random roll that favours offensive moves, so the AI leans aggressive
 * without being deterministic.
 *
 * <p><b>Placement.</b>
 * <ul>
 *   <li>Offensive / utility moves are grouped early on the offensive board:
 *       each is placed right after the last placed segment (tick 1 if the board
 *       is empty), with a small random 0–2 tick gap for subtle variety.</li>
 *   <li>Defensive moves align their <em>fire tick</em> with a randomly chosen
 *       placed offensive segment's fire tick, so blocks land when attacks do.
 *       If that alignment can't be placed, they fall back to grouped-early.</li>
 * </ul>
 *
 * <p>Because the timelines only accumulate segments during a round, free grid
 * space only shrinks. A move that fails to place once can never fit later this
 * round, so it is added to a per-round {@code stuck} set and never retried —
 * this is what guarantees the loop terminates alongside the shrinking budgets.
 */
public class GreedyAIStrategy implements AIStrategy {

    // Weighting for the weighted-random pick. Offensive is always attractive;
    // defensive and utility are de-prioritised but still viable for variety.
    private static final double WEIGHT_OFFENSIVE = 6.0;
    private static final double WEIGHT_DEFENSIVE = 2.5;
    private static final double WEIGHT_UTILITY   = 1.0;
    /** Defensive moves are only considered once at least one offensive is down. */

    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe());
        AbilityFlags abilityFlags = ai.getAbilityFlags();
        List<Move> knownMoves = ai.getCharacter().getKnownMoves();

        // Moves that failed placement this round — never retried (see class doc).
        Set<Move> stuck = new HashSet<>();

        while (true) {
            List<Move> candidates = affordableMoves(ai, knownMoves, plan, abilityFlags, stuck);
            if (candidates.isEmpty()) break;

            Move pick = weightedRandomPick(candidates, plan, rng);
            boolean placed = tryPlace(pick, ai, plan, rng);
            if (!placed) stuck.add(pick);
        }

        return plan;
    }

    // -------------------------------------------------------------------------
    // Selection
    // -------------------------------------------------------------------------

    /** Known moves that fit both budgets, aren't ability-locked, and aren't stuck this round. */
    private List<Move> affordableMoves(BattleCombatant ai, List<Move> knownMoves, BattlePlan plan,
                                       AbilityFlags abilityFlags, Set<Move> stuck) {
        int ceEfficiency = ai.getEffectiveStats().getCursedEnergyEfficiency();
        List<Move> affordable = new ArrayList<>();
        for (Move move : knownMoves) {
            if (stuck.contains(move)) continue;
            if (abilityFlags.lockedMoveTags.stream().anyMatch(move::hasTag)) continue;
            int ceCost = CeEfficiencyCalculator.computeActualCost(move, ceEfficiency, abilityFlags);
            if (plan.fitsBudgets(move, ceCost)) affordable.add(move);
        }
        return affordable;
    }

    /**
     * Weighted-random pick favouring offensive moves. Defensive moves are only
     * eligible once at least one offensive segment has been placed (so a block
     * always has an attack to align with); before that, defensives are filtered
     * out unless the AI has no offensive moves at all.
     */
    private Move weightedRandomPick(List<Move> candidates, BattlePlan plan, RandomSource rng) {
        boolean hasOffensePlaced = !plan.offensiveTimeline().isEmpty();

        // Partition + filter.
        List<Move> offensive = new ArrayList<>();
        List<Move> defensive = new ArrayList<>();
        List<Move> utility   = new ArrayList<>();
        for (Move m : candidates) {
            if (m.hasTag("ATTACK")) offensive.add(m);
            else if (m.isDefensive()) defensive.add(m);
            else utility.add(m);
        }

        // Defensive gate: only consider blocks once there's something to protect,
        // unless offense isn't an option at all this round.
        if (!hasOffensePlaced && !offensive.isEmpty()) {
            defensive.clear();
        }

        // Build the weighted pool.
        List<Move> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Move m : offensive) { pool.add(m); weights.add(WEIGHT_OFFENSIVE); }
        for (Move m : defensive) { pool.add(m); weights.add(WEIGHT_DEFENSIVE); }
        for (Move m : utility)   { pool.add(m); weights.add(WEIGHT_UTILITY);   }

        if (pool.isEmpty()) return candidates.get(rng.nextInt(candidates.size()));

        double total = 0;
        for (double w : weights) total += w;
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    private boolean tryPlace(Move move, BattleCombatant ai, BattlePlan plan, RandomSource rng) {
        int ceEfficiency = ai.getEffectiveStats().getCursedEnergyEfficiency();
        AbilityFlags abilityFlags = ai.getAbilityFlags();
        int ceCost = CeEfficiencyCalculator.computeActualCost(move, ceEfficiency, abilityFlags);

        if (move.isDefensive()) {
            return placeDefensive(move, ceCost, plan, rng)
                || placeGroupedEarly(move, ceCost, plan);
        }
        return placeGroupedEarly(move, ceCost, plan);
    }

    /**
     * Grouped-early placement on the move's assigned board: right after the last
     * placed segment (or tick 1 on an empty board) plus a small random gap.
     */
    private boolean placeGroupedEarly(Move move, int ceCost, BattlePlan plan) {
        Timeline board = plan.boardTimeline(BattlePlan.boardFor(move));
        int start = nextFreeStart(board, move);
        ActionSegment segment = plan.place(move, start, ceCost);
        return segment != null;
    }

    /**
     * Align this defensive move's fire tick with a randomly chosen placed
     * offensive segment's fire tick, so the block is active when the attack lands.
     * Returns false if no offensive segment exists or the alignment won't fit.
     */
    private boolean placeDefensive(Move move, int ceCost, BattlePlan plan, RandomSource rng) {
        List<ActionSegment> offense = plan.offensiveTimeline().getSegments();
        if (offense.isEmpty()) return false;

        ActionSegment anchor = offense.get(rng.nextInt(offense.size()));
        int targetFireTick = anchor.getFireTick();
        // startTick so that startTick + unleashPoint - 1 == targetFireTick.
        int start = Math.max(1, targetFireTick - move.getUnleashPoint() + 1);
        ActionSegment segment = plan.place(move, start, ceCost);
        return segment != null;
    }

    /** First start tick at which {@code move} fits after the last segment on {@code board}. */
    private int nextFreeStart(Timeline board, Move move) {
        int cursor = 1;
        for (ActionSegment s : board.getSegments()) {
            cursor = Math.max(cursor, s.getEndTick() + 1);
        }
        return cursor;
    }
}
