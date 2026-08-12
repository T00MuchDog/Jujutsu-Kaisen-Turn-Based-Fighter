package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.PowerCalculator;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggressive archetype for technique-less sorcerers (e.g. Yuji, Maki).
 *
 * <p>Press hard: attack-focused with only a low chance of a defensive move,
 * prioritise high-base-power attacks, prefer cursed-energy ("reinforcement")
 * moves over purely physical ones, and rotate the arsenal (each repeat of a
 * move is slightly de-weighted so the AI varies its offence). CE is spent
 * freely — no frugality. Placement favours two bunched clusters, one at the
 * start and one at the end, with the occasional mid-timeline move.
 *
 * <p>Selection is a greedy loop weighted by
 * {@code basePower × power × effect-bonus × CE/physical preference × diversity
 * × dodge/defense exposure × reinforcement/crack bonuses}, composed from the
 * shared {@link SmartAIScoring} factors. When it does defend, it picks the
 * single most useful defense (see {@link SmartAIScoring#defenseValue}) aligned
 * to the opponent's biggest committed attack.
 */
public class AggressiveSorcererAIStrategy implements AIStrategy {

    // --- Aggressive tunables (code-only) ---
    /** Chance (after the first attack) to spend a pick on a defensive move. */
    private static final double DEFENSE_CHANCE = 0.12;
    /** Cursed-energy ("reinforcement") attack base preference. */
    private static final double CE_WEIGHT = 1.0;
    /** Purely-physical attack base preference (aggressive disfavours these). */
    private static final double PHYSICAL_WEIGHT = 0.4;
    /** Attacks that are neither CE nor purely physical. */
    private static final double OTHER_ATTACK_WEIGHT = 0.7;
    /** Per prior placement, a move's weight multiplies by this (diversity). */
    private static final double DIVERSITY_FACTOR = 0.75;
    /** Attack weight bump when the opponent is low-HP ("press the lead"). */
    private static final double PRESS_BONUS = 1.3;
    /** Opponent HP fraction below which attacks are pressed harder. */
    private static final double LOW_HP_OPPONENT_THRESHOLD = 0.30;
    /** Placement cluster probabilities (must sum to 1). */
    private static final double START_CLUSTER_PROB = 0.45;
    private static final double END_CLUSTER_PROB = 0.45;

    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        int gridLength = Timeline.gridLengthForStrongestAp(
            Math.max(ai.getMaxApBar(), opponent == null ? 0 : opponent.getMaxApBar()));
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), gridLength);
        OpponentIntel intel = OpponentIntel.forOpponent(opponent);

        List<Move> attacks = new ArrayList<>();
        List<Move> defenses = new ArrayList<>();
        for (Move move : ai.getCharacter().getKnownMoves()) {
            if (!MoveAvailability.isAvailable(null, ai, move)) continue;
            if (move.hasTag("ATTACK")) {
                attacks.add(move);
            } else if (move.isActiveDefense()) {
                defenses.add(move);
            }
        }

        boolean lowHpOpponent = opponent != null
            && (double) opponent.getCurrentHp() / Math.max(1, opponent.getMaxHp())
               < LOW_HP_OPPONENT_THRESHOLD;
        Set<Move> stuck = new HashSet<>();
        boolean hasAttacked = false;

        while (true) {
            Move pick = chooseNext(ai, plan, attacks, defenses, intel, stuck, rng, hasAttacked, lowHpOpponent);
            if (pick == null) break;
            int ceCost = ai.computeMoveCeCost(pick);
            boolean placed;
            if (pick.isDefensive()) {
                placed = placeDefense(pick, ceCost, plan, gridLength, ai, opponent, intel);
            } else {
                placed = placeAttack(pick, ceCost, plan, gridLength, rng) != null;
            }
            if (!placed) {
                stuck.add(pick);
            } else if (!pick.isDefensive()) {
                hasAttacked = true;
            }
        }
        return plan;
    }

    // -------------------------------------------------------------------------
    // Selection
    // -------------------------------------------------------------------------

    private Move chooseNext(
        BattleCombatant ai, BattlePlan plan, List<Move> attacks, List<Move> defenses,
        OpponentIntel intel, Set<Move> stuck, RandomSource rng,
        boolean hasAttacked, boolean lowHpOpponent
    ) {
        // Low chance of a defensive pick, but only after the offence is rolling
        // and only when a useful defense actually fits.
        if (hasAttacked && rng.nextDouble() < DEFENSE_CHANCE) {
            Move defense = bestUsefulDefense(ai, plan, defenses, intel, stuck);
            if (defense != null) return defense;
        }
        return pickAttack(ai, plan, attacks, intel, stuck, rng, lowHpOpponent);
    }

    private Move pickAttack(
        BattleCombatant ai, BattlePlan plan, List<Move> attacks, OpponentIntel intel,
        Set<Move> stuck, RandomSource rng, boolean lowHpOpponent
    ) {
        List<Move> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Move m : attacks) {
            if (stuck.contains(m) || !plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
            pool.add(m);
            weights.add(attackWeight(m, ai, plan, intel, lowHpOpponent));
        }
        return SmartAIScoring.weightedRandomPick(pool, weights, rng);
    }

    static double attackWeight(
        Move move, BattleCombatant ai, BattlePlan plan, OpponentIntel intel, boolean lowHpOpponent
    ) {
        double basePower = Math.max(1, move.getTotalBasePower());
        double power = Math.max(1, PowerCalculator.compute(move.getCategory(), ai.getEffectiveStats()));
        double weight = basePower * power;

        weight *= SmartAIScoring.effectMultiplier(move);
        weight *= SmartAIScoring.dodgeExposureMultiplier(move, intel);
        weight *= SmartAIScoring.reinforcementAttackMultiplier(move, intel);
        weight *= SmartAIScoring.defenseCrackMultiplier(move, intel);
        weight *= damageNaturePreference(move);
        weight *= Math.pow(DIVERSITY_FACTOR, plan.selectedUses(move)); // diversity: de-weight repeats
        if (lowHpOpponent) weight *= PRESS_BONUS;
        return weight;
    }

    private static double damageNaturePreference(Move move) {
        if (SmartAIScoring.isReinforcement(move)) return CE_WEIGHT;
        if (SmartAIScoring.isPhysicalAttack(move)) return PHYSICAL_WEIGHT;
        return OTHER_ATTACK_WEIGHT;
    }

    /** The single highest-value defense that fits and is useful vs this opponent. */
    private Move bestUsefulDefense(
        BattleCombatant ai, BattlePlan plan, List<Move> defenses, OpponentIntel intel, Set<Move> stuck
    ) {
        Move best = null;
        double bestValue = 0;
        for (Move d : defenses) {
            if (stuck.contains(d) || !plan.canPlace(d, ai.computeMoveCeCost(d))) continue;
            double value = SmartAIScoring.defenseValue(d, intel);
            if (value > bestValue) {
                bestValue = value;
                best = d;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    /** Bunched clusters: high chance at the start and end, occasional mid move. */
    private ActionSegment placeAttack(Move move, int ceCost, BattlePlan plan, int gridLength, RandomSource rng) {
        double roll = rng.nextDouble();
        if (roll < START_CLUSTER_PROB) {
            return SmartAIScoring.placeAtOrAfter(plan, move, ceCost, 1);
        }
        if (roll < START_CLUSTER_PROB + END_CLUSTER_PROB) {
            return SmartAIScoring.placeBunchedAtEnd(plan, move, ceCost, gridLength);
        }
        return SmartAIScoring.placeAtOrAfter(plan, move, ceCost, Math.max(1, gridLength / 3));
    }

    /** Align the rare defense to the opponent's biggest committed attack, else bunch at the start. */
    private boolean placeDefense(
        Move move, int ceCost, BattlePlan plan, int gridLength,
        BattleCombatant ai, BattleCombatant opponent, OpponentIntel intel
    ) {
        if (!intel.committedAttackFireTicks.isEmpty()) {
            int biggest = intel.committedAttackFireTicks.get(intel.committedAttackFireTicks.size() - 1);
            ActionSegment aligned = SmartAIScoring.placeAlignedToThreat(
                plan, move, ceCost, biggest, ai, opponent);
            if (aligned != null) return true;
        }
        return SmartAIScoring.placeAtOrAfter(plan, move, ceCost, 1) != null;
    }
}
