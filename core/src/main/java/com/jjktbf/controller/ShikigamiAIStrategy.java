package com.jjktbf.controller;

import com.jjktbf.model.character.coded.ShikigamiMoveRuntime;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.PowerCalculator;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveEffectData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI archetype for summoned shikigami.
 *
 * <p>Shikigami are simple, offense-first summons: a desummon (self-dismissal)
 * move plus a handful of attacks, occasionally a utility move. This archetype
 * makes them play that way — press with attacks, prefer the hardest-hitting and
 * effect-bearing options, respect the opponent's committed melee/ranged dodges
 * and blocks, spread attacks across the round, spend the AP bar, and bail out
 * (desummon) when nearly dead or on a small random chance.
 *
 * <p><b>"The AI knows the player's moves."</b> The opponent's committed plan is
 * readable here because the player's team plan is attached to combatants before
 * the AI is invoked (see {@code BattleController#runPlanningPhase}). This
 * archetype uses that to count the player's pending melee/ranged dodges and
 * blocks and factor them into attack selection.
 *
 * <p><b>Selection</b> is weighted-random by a per-attack score, so the strongest
 * option is favoured but the AI still varies round to round (less robotic than a
 * deterministic max pick). <b>Placement</b> spreads the chosen attacks evenly
 * across the offensive timeline rather than clumping them at the start.
 *
 * <p>Defensive moves are intentionally never placed: the archetype is pure
 * offense, matching the shikigami design ("focus on attacking"). All tunables
 * are collected as constants at the bottom of the class.
 */
public class ShikigamiAIStrategy implements AIStrategy {

    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        int gridLength = Timeline.gridLengthForStrongestAp(
            Math.max(ai.getMaxApBar(), opponent == null ? 0 : opponent.getMaxApBar()));
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), gridLength);

        // Partition the move pool. Defensive moves are dropped on purpose.
        Move desummon = null;
        List<Move> attacks = new ArrayList<>();
        List<Move> utilities = new ArrayList<>();
        for (Move move : ai.getCharacter().getKnownMoves()) {
            if (!MoveAvailability.isAvailable(null, ai, move)) continue;
            if (isDesummonSelf(move)) {
                if (desummon == null) desummon = move;
            } else if (move.hasTag("ATTACK")) {
                attacks.add(move);
            } else if (move.hasTag("UTILITY")) {
                utilities.add(move);
            }
        }

        // --- Bail: nearly dead -> dismiss self immediately (no attacks). ---
        // A voluntarily-desummoned shikigami can return next round; a destroyed
        // one is gone for the battle, so fleeing on the brink is correct play.
        double hpRatio = (double) ai.getCurrentHp() / Math.max(1, ai.getMaxHp());
        if (hpRatio < DESUMMON_HP_THRESHOLD) {
            if (desummon != null) {
                plan.place(desummon, 1, ai.computeMoveCeCost(desummon));
            }
            return plan;
        }

        OpponentDefenses defenses = countOpponentDefenses(opponent);

        // --- Phase 1: greedily pick an ordered attack sequence (virtual budgets). ---
        // Track AP/CE spent virtually so placement in phase 2 is guaranteed to fit.
        int remainingAp = plan.remainingApBudget();
        int remainingCe = plan.remainingCe();
        Map<String, Integer> uses = new HashMap<>();
        List<Move> picked = new ArrayList<>();
        boolean bailForDesummon = false;
        while (true) {
            // Tiny per-choice chance to spontaneously bail, even at full HP.
            if (desummon != null && rng.nextDouble() < RANDOM_DESUMMON_CHANCE) {
                bailForDesummon = true;
                break;
            }
            Move pick = weightedPick(attacks, ai, defenses, remainingAp, remainingCe, uses, rng);
            if (pick == null) break;
            picked.add(pick);
            remainingAp -= pick.getApCost();
            remainingCe -= ai.computeMoveCeCost(pick);
            uses.merge(pick.getId(), 1, Integer::sum);
        }

        // --- Phase 2: spread the picked attacks evenly across the offensive board. ---
        // Even spacing distributes the shikigami's few attacks across the whole
        // round (looks human, harder to counter). Fall back to first-fit when the
        // ideal slot is taken so the AP still gets spent.
        int stride = picked.isEmpty() ? 0 : Math.max(1, gridLength / (picked.size() + 1));
        for (int i = 0; i < picked.size(); i++) {
            Move m = picked.get(i);
            int ceCost = ai.computeMoveCeCost(m);
            ActionSegment placed = plan.place(m, 1 + i * stride, ceCost);
            if (placed == null) {
                plan.placeFirstFit(m, ceCost);
            }
        }

        // --- Rare non-desummon utility (only when we actually attacked). ---
        if (!bailForDesummon && !picked.isEmpty() && !utilities.isEmpty()
                && rng.nextDouble() < UTILITY_CHANCE) {
            Move u = utilities.get(rng.nextInt(utilities.size()));
            int ceCost = ai.computeMoveCeCost(u);
            if (plan.canPlace(u, ceCost)) {
                plan.placeFirstFit(u, ceCost);
            }
        }

        // --- Random desummon bail: fight through the round, then leave. ---
        // Desummon is a utility move (defensive board), so it never collides with
        // the attacks above. Place it at the end so committed attacks fire first;
        // if nothing was picked, leave immediately (tick 1).
        if (bailForDesummon && desummon != null) {
            int ceCost = ai.computeMoveCeCost(desummon);
            if (plan.canPlace(desummon, ceCost)) {
                int start = picked.isEmpty()
                    ? 1
                    : Math.max(1, gridLength - desummon.getApCost() + 1);
                if (plan.place(desummon, start, ceCost) == null) {
                    plan.placeFirstFit(desummon, ceCost);
                }
            }
        }

        return plan;
    }

    // -------------------------------------------------------------------------
    // Selection
    // -------------------------------------------------------------------------

    /**
     * Weighted-random attack pick from the moves that still fit the virtual
     * budgets and per-round use cap. Weight is the attack's score, so
     * harder-hitting, effect-bearing, defense-aware attacks are chosen more
     * often without the pick ever becoming deterministic.
     */
    private static Move weightedPick(List<Move> attacks, BattleCombatant ai, OpponentDefenses defenses,
                                     int remainingAp, int remainingCe, Map<String, Integer> uses,
                                     RandomSource rng) {
        List<Move> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Move m : attacks) {
            if (m.getMoveCap() != 0 && uses.getOrDefault(m.getId(), 0) >= m.getMoveCap()) continue;
            int ceCost = ai.computeMoveCeCost(m);
            if (m.getApCost() > remainingAp || ceCost > remainingCe) continue;
            pool.add(m);
            weights.add(Math.max(MIN_WEIGHT, scoreAttack(m, ai, defenses)));
        }
        if (pool.isEmpty()) return null;

        double total = 0;
        for (double w : weights) total += w;
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * Relative attractiveness of one attack: raw damage (authored base power ×
     * the attacker's power for the move's category), boosted when the move
     * carries effect rows, and dampened by the opponent's committed defenses
     * that would negate or reduce it.
     */
    static double scoreAttack(Move move, BattleCombatant ai, OpponentDefenses defenses) {
        int basePower = Math.max(1, move.getTotalBasePower());
        int power = Math.max(1, PowerCalculator.compute(move.getCategory(), ai.getEffectiveStats()));
        double score = (double) basePower * power;
        if (hasMeaningfulEffects(move)) {
            score *= EFFECT_BONUS;
        }
        return score * defensePenalty(move, defenses);
    }

    /** Whether a move carries any authored effect rows (on-hit tech, buffs, ...). */
    private static boolean hasMeaningfulEffects(Move move) {
        List<MoveEffectData> effects = move.getEffects();
        return effects != null && !effects.isEmpty();
    }

    /**
     * Multiplier in (0, 1] that reduces an attack's score when the opponent has
     * committed defenses it would apply to: melee-scoped dodges penalise melee
     * attacks, ranged-scoped dodges penalise ranged attacks, and any block/parry
     * penalises both mildly (a block only reduces damage, it doesn't negate it).
     */
    private static double defensePenalty(Move move, OpponentDefenses defenses) {
        boolean melee = move.hasTag("MELEE");
        boolean ranged = move.hasTag("RANGED");
        if (melee && !ranged) {
            return 1.0 / (1.0 + DODGE_WEIGHT * defenses.meleeDodge
                                + BLOCK_WEIGHT * defenses.blockParry);
        }
        if (ranged && !melee) {
            return 1.0 / (1.0 + DODGE_WEIGHT * defenses.rangedDodge
                                + BLOCK_WEIGHT * defenses.blockParry);
        }
        // Both range tags or neither: average the dodge exposure.
        double dodgeExposure = (defenses.meleeDodge + defenses.rangedDodge) * 0.5;
        return 1.0 / (1.0 + DODGE_WEIGHT * dodgeExposure + BLOCK_WEIGHT * defenses.blockParry);
    }

    // -------------------------------------------------------------------------
    // Opponent awareness
    // -------------------------------------------------------------------------

    /**
     * Count the melee/ranged dodges and blocks/parries in the opponent's
     * committed plan. Returns zeros when there is no opponent or no plan yet.
     * Delegates to the shared {@link OpponentIntel} so every archetype reads the
     * opponent the same way.
     */
    static OpponentDefenses countOpponentDefenses(BattleCombatant opponent) {
        OpponentIntel intel = OpponentIntel.forOpponent(opponent);
        OpponentDefenses d = new OpponentDefenses();
        d.meleeDodge = intel.committedMeleeDodge;
        d.rangedDodge = intel.committedRangedDodge;
        d.blockParry = intel.committedBlock + intel.committedParry;
        return d;
    }

    /** True if this move dismisses the shikigami that fires it (the desummon self-move). */
    static boolean isDesummonSelf(Move move) {
        if (move == null) return false;
        List<MoveEffectData> effects = move.getEffects();
        if (effects == null) return false;
        for (MoveEffectData effect : effects) {
            if (ShikigamiMoveRuntime.KEY.equalsIgnoreCase(effect.codedAbilityKey)
                && ShikigamiMoveRuntime.DESUMMON_SELF.equalsIgnoreCase(effect.codedAction)) {
                return true;
            }
        }
        return false;
    }

    /** Pending defenses read from the opponent's committed plan. */
    static final class OpponentDefenses {
        int meleeDodge;
        int rangedDodge;
        int blockParry;
    }

    // -------------------------------------------------------------------------
    // Tunables (code-only; not exposed in data or the editor)
    // -------------------------------------------------------------------------

    /** Below this HP fraction the shikigami immediately desummons (flees). */
    private static final double DESUMMON_HP_THRESHOLD = 0.10;
    /** Per-move-choice chance to spontaneously desummon, even at full HP. */
    private static final double RANDOM_DESUMMON_CHANCE = 0.02;
    /** Chance to also commit a non-desummon utility move when one exists. */
    private static final double UTILITY_CHANCE = 0.10;
    /** Attacks carrying effect rows are weighted this much higher. */
    private static final double EFFECT_BONUS = 1.5;
    /** Per matching opponent dodge, an attack's score divides by {@code 1 + this·count}. */
    private static final double DODGE_WEIGHT = 0.6;
    /** Per opponent block/parry, an attack's score divides by {@code 1 + this·count}. */
    private static final double BLOCK_WEIGHT = 0.25;
    /** Floor so even a weak attack has a small chance to be picked. */
    private static final double MIN_WEIGHT = 0.5;
}
