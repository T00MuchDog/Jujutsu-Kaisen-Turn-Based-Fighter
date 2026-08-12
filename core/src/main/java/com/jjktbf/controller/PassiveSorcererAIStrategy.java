package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Passive archetype for technique-less sorcerers (e.g. Miwa).
 *
 * <p>A measured, reactive style: pour roughly two thirds of the AP bar into
 * defense, prefer light and quick moves, and spend cursed energy slowly by
 * weaving purely physical attacks in (CE is conserved to sustain defenses).
 * The best defense goes at the start of the round, the next at the middle, the
 * next at the end, and any remainder scattered; attacks usually place one at
 * the start and the rest at random.
 *
 * <p>Defenses are committed before attacks so the defensive AP reserve is
 * honoured, and only defenses that are actually useful vs this opponent are
 * placed (see {@link SmartAIScoring#defenseValue} — a block that covers no
 * opponent threat, can't contest the opponent's potency, or is an over-broad
 * "reinforced" block against a purely physical opponent scores zero and is
 * skipped). Attack weights favour cheap, quick, physical, effect-bearing moves.
 */
public class PassiveSorcererAIStrategy implements AIStrategy {

    // --- Passive tunables (code-only) ---
    /** Fraction of the AP bar reserved for defense. */
    private static final double DEFENSE_AP_FRACTION = 2.0 / 3.0;
    /** Purely-physical attack base preference (passive favours these — CE-frugal). */
    private static final double PHYSICAL_WEIGHT = 1.0;
    /** Any CE-bearing attack (reinforcement or pure CE) is de-weighted to conserve CE. */
    private static final double CE_WEIGHT = 0.4;
    /** Reference AP cost at which the quickness factor is 1.0 (cheaper = higher). */
    private static final double QUICKNESS_REF = 12.0;
    /** Bonus for instant (unleash-point 1) moves. */
    private static final double INSTANT_UNLEASH_BONUS = 1.3;

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

        int defenseApBudget = (int) Math.round(DEFENSE_AP_FRACTION * ai.getMaxApBar());
        placeDefenses(ai, plan, defenses, intel, defenseApBudget, gridLength, rng);
        placeAttacks(ai, plan, attacks, intel, gridLength, rng);
        return plan;
    }

    // -------------------------------------------------------------------------
    // Defenses first: best -> start, then middle, then end, then scattered
    // -------------------------------------------------------------------------

    private void placeDefenses(
        BattleCombatant ai, BattlePlan plan, List<Move> defenses, OpponentIntel intel,
        int defenseApBudget, int gridLength, RandomSource rng
    ) {
        // Rank useful defenses by value (coverage × quality × counterplay).
        List<Move> ranked = new ArrayList<>();
        for (Move d : defenses) {
            if (SmartAIScoring.defenseValue(d, intel) > 0
                && plan.canPlace(d, ai.computeMoveCeCost(d))) {
                ranked.add(d);
            }
        }
        ranked.sort(Comparator.comparingDouble(
            (Move d) -> SmartAIScoring.defenseValue(d, intel)).reversed());

        int defenseApUsed = 0;
        int placedIndex = 0;
        for (Move d : ranked) {
            if (defenseApUsed >= defenseApBudget) break;
            int ceCost = ai.computeMoveCeCost(d);
            if (!plan.canPlace(d, ceCost)) continue;

            ActionSegment seg = switch (placedIndex) {
                case 0  -> SmartAIScoring.placeAtOrAfter(plan, d, ceCost, 1);             // start
                case 1  -> SmartAIScoring.placeAtOrAfter(plan, d, ceCost, gridLength / 2); // middle
                case 2  -> SmartAIScoring.placeBunchedAtEnd(plan, d, ceCost, gridLength);  // end
                default -> SmartAIScoring.placeAtFreeRandom(plan, d, ceCost, gridLength, rng); // scatter
            };
            if (seg != null) {
                defenseApUsed += d.getApCost();
                placedIndex++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Attacks: one at the start, the rest placed randomly
    // -------------------------------------------------------------------------

    private void placeAttacks(
        BattleCombatant ai, BattlePlan plan, List<Move> attacks, OpponentIntel intel,
        int gridLength, RandomSource rng
    ) {
        boolean firstAttack = true;
        // Keep placing attacks weighted toward cheap/quick/physical until none fit.
        while (true) {
            List<Move> pool = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (Move m : attacks) {
                if (!plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
                pool.add(m);
                weights.add(attackWeight(m, intel));
            }
            Move pick = SmartAIScoring.weightedRandomPick(pool, weights, rng);
            if (pick == null) break;

            int ceCost = ai.computeMoveCeCost(pick);
            ActionSegment seg = firstAttack
                ? SmartAIScoring.placeAtOrAfter(plan, pick, ceCost, 1)
                : SmartAIScoring.placeAtFreeRandom(plan, pick, ceCost, gridLength, rng);
            if (seg == null) {
                // No room for this one anywhere — drop it and try the rest.
                attacks.remove(pick);
                continue;
            }
            firstAttack = false;
        }
    }

    static double attackWeight(Move move, OpponentIntel intel) {
        double quickness = QUICKNESS_REF / Math.max(1, move.getApCost());
        if (move.getUnleashPoint() == 1) quickness *= INSTANT_UNLEASH_BONUS;
        double weight = quickness;
        weight *= damageNaturePreference(move);
        weight *= SmartAIScoring.effectMultiplier(move);
        weight *= SmartAIScoring.dodgeExposureMultiplier(move, intel);
        return weight;
    }

    private static double damageNaturePreference(Move move) {
        // CE-frugal: favour purely physical attacks; any CE-bearing move
        // (reinforcement or pure CE) spends CE and is de-weighted.
        if (SmartAIScoring.isPhysicalAttack(move)) return PHYSICAL_WEIGHT;
        return CE_WEIGHT;
    }
}
