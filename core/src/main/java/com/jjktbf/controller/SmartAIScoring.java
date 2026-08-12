package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveEffectData;

import java.util.List;

/**
 * Shared "smart AI" scoring and placement helpers used by every archetype.
 *
 * <p>This is pure composition utility: each archetype keeps its own
 * {@code selectPlan} loop and tunables, and multiplies a base preference by the
 * adjustment factors defined here so the same tactical heuristics apply
 * everywhere. The factors encode the opponent-aware rules:
 *
 * <ul>
 *   <li>{@link #effectMultiplier} — weight moves carrying effect rows higher.</li>
 *   <li>{@link #dodgeExposureMultiplier} — de-weight an attack when the opponent
 *       has committed matching (melee/ranged) dodges or blocks.</li>
 *   <li>{@link #reinforcementAttackMultiplier} — boost cursed-energy
 *       ("reinforcement") attacks when the opponent's blocks are physical-only
 *       (CE slips through physical blocks).</li>
 *   <li>{@link #defenseValue} — score a defensive move by whether it actually
 *       covers an opponent threat, its potency vs the opponent's strongest
 *       attack, whether it's an over-broad "reinforced" block the opponent
 *       doesn't need, and guard-break/intangible counterplay.</li>
 * </ul>
 *
 * <p>Placement helpers ({@link #placeAtOrAfter}, {@link #placeAtFreeRandom},
 * {@link #placeAlignedToThreat}) give each archetype's positioning its shape.
 */
final class SmartAIScoring {

    private SmartAIScoring() { }

    // --- Shared tunables (code-only) ---
    /** Moves carrying effect rows are weighted this much higher. */
    static final double EFFECT_BONUS = 1.5;
    /** Per matching committed dodge, an attack's score divides by {@code 1 + this·count}. */
    static final double DODGE_WEIGHT = 0.6;
    /** Per committed block/parry, an attack's score divides by {@code 1 + this·count}. */
    static final double COMMITTED_BLOCK_WEIGHT = 0.25;
    /** CE attack bonus when the opponent's blocks can't stop CE (physical-only blocks). */
    static final double REINFORCEMENT_BYPASS_BONUS = 1.8;
    /** Guard-break/intangible attack bonus into an opponent turtling behind blocks/parries. */
    static final double DEFENSE_CRACK_BONUS = 1.5;
    /** Defense multiplier when its potency is below the opponent's strongest attack. */
    static final double POTENCY_FAIL = 0.3;
    /** Broader-than-needed (reinforced) block vs a physical-only opponent. */
    static final double OVER_REINFORCED = 0.7;
    /** Block value when the opponent owns guard-break attacks (blocks get bypassed). */
    static final double GUARDBREAK_BLOCK_PENALTY = 0.4;
    /** Block/parry value when the opponent owns intangible attacks (both are bypassed). */
    static final double INTANGIBLE_DEFENSE_PENALTY = 0.4;
    /** Floor so a usable candidate is never picked with zero probability. */
    static final double MIN_WEIGHT = 0.05;
    /** Random-placement attempts before falling back to first-fit. */
    static final int RANDOM_PLACE_TRIES = 12;

    // -------------------------------------------------------------------------
    // Move classification
    // -------------------------------------------------------------------------

    /**
     * A "reinforcement" attack — a physical strike reinforced with cursed energy,
     * i.e. carrying both PHYSICAL and CURSED_ENERGY (the PHYSICAL_CURSED_ENERGY
     * typing). Pure-CE blasts and technique attacks are <em>not</em> "reinforcement".
     */
    static boolean isReinforcement(Move move) {
        return move != null && move.hasTag("PHYSICAL") && move.hasTag("CURSED_ENERGY");
    }

    /** A purely physical attack — PHYSICAL tag and no cursed energy. */
    static boolean isPhysicalAttack(Move move) {
        return move != null && move.hasTag("PHYSICAL") && !move.hasTag("CURSED_ENERGY");
    }

    /**
     * Whether a block's affected-tags cover cursed energy. A block with no
     * declared damage tags covers everything (per {@link Move#coveredByBlockTags}).
     */
    static boolean blockCoversCursedEnergy(Move block) {
        if (block == null) return false;
        List<String> tags = block.getBlockAffectedTags();
        if (tags == null || tags.isEmpty()) return true;
        for (String tag : tags) {
            if ("CURSED_ENERGY".equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    private static boolean hasMeaningfulEffects(Move move) {
        List<MoveEffectData> effects = move.getEffects();
        return effects != null && !effects.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Attack scoring factors
    // -------------------------------------------------------------------------

    static double effectMultiplier(Move move) {
        return hasMeaningfulEffects(move) ? EFFECT_BONUS : 1.0;
    }

    /**
     * De-weight an attack according to the opponent's committed defenses:
     * melee dodges penalise melee attacks, ranged dodges penalise ranged
     * attacks, and committed blocks/parries penalise everything mildly.
     */
    static double dodgeExposureMultiplier(Move attack, OpponentIntel intel) {
        boolean melee = attack.isMelee();
        boolean ranged = attack.isRanged();
        int blockers = intel.committedBlock + intel.committedParry;
        if (melee && !ranged) {
            return 1.0 / (1.0 + DODGE_WEIGHT * intel.committedMeleeDodge
                                + COMMITTED_BLOCK_WEIGHT * blockers);
        }
        if (ranged && !melee) {
            return 1.0 / (1.0 + DODGE_WEIGHT * intel.committedRangedDodge
                                + COMMITTED_BLOCK_WEIGHT * blockers);
        }
        double dodgeExposure = (intel.committedMeleeDodge + intel.committedRangedDodge) * 0.5;
        return 1.0 / (1.0 + DODGE_WEIGHT * dodgeExposure + COMMITTED_BLOCK_WEIGHT * blockers);
    }

    /**
     * Boost reinforcement (PHYSICAL + CURSED_ENERGY) attacks when the opponent's
     * blocks are physical-only — those attacks slip straight through.
     */
    static double reinforcementAttackMultiplier(Move attack, OpponentIntel intel) {
        if (intel.blocksPhysicalOnly && isReinforcement(attack)) {
            return REINFORCEMENT_BYPASS_BONUS;
        }
        return 1.0;
    }

    /**
     * Bonus for attacks that crack a turtling opponent: guard-break bypasses
     * blocks, intangible bypasses blocks and parries.
     */
    static double defenseCrackMultiplier(Move attack, OpponentIntel intel) {
        int turtling = intel.committedBlock + intel.committedParry;
        if (turtling <= 0) return 1.0;
        if (attack.isGuardBreak() || attack.isIntangible()) return DEFENSE_CRACK_BONUS;
        return 1.0;
    }

    // -------------------------------------------------------------------------
    // Defense scoring
    // -------------------------------------------------------------------------

    /**
     * Raw coverage of a block vs the opponent's authored attacks: does it stop
     * at least one attack they can actually throw? Returns 0 when the block
     * matches no opponent attack (a wasted block), otherwise a positive value
     * reduced for low potency and for being broader than a physical-only
     * opponent requires.
     */
    static double blockUsefulness(Move block, OpponentIntel intel) {
        if (intel.attacks.isEmpty()) return 0;
        boolean coversAny = false;
        for (Move attack : intel.attacks) {
            if (attack.coveredByBlockTags(block.getBlockAffectedTags())) {
                coversAny = true;
                break;
            }
        }
        if (!coversAny) return 0.0;

        double score = 1.0;
        if (block.getPotency() < intel.maxAttackPotency) {
            score *= POTENCY_FAIL; // can't contest the opponent's strongest attack
        }
        // Opponent is purely physical: a block that also covers CE is broader
        // than needed — prefer the minimal physical block.
        if (intel.physicalOnly && blockCoversCursedEnergy(block)) {
            score *= OVER_REINFORCED;
        }
        return score;
    }

    /**
     * Full value of a defensive move for this opponent: 0 if it is useless
     * (covers no threat / wrong scope), else a positive score combining coverage,
     * quality (reduction / full-negation), and guard-break/intangible risk.
     */
    static double defenseValue(Move defense, OpponentIntel intel) {
        double base;
        if (defense.isBlock()) {
            base = blockUsefulness(defense, intel);
            if (base <= 0) return 0.0;
            base *= 1.0 + defense.getBlockDamageReduction() / 100.0;
        } else if (defense.isParry()) {
            base = (defense.getPotency() < intel.maxAttackPotency) ? POTENCY_FAIL : 1.0;
            base *= 1.2; // a parry fully negates — high value when it can contest
        } else if (defense.isDodge()) {
            base = dodgeScopeRelevance(defense, intel);
            base *= 1.1;
        } else {
            return 0.0; // not an active defense
        }
        return base * defenseCounterplayMultiplier(defense, intel);
    }

    /** How relevant a dodge's scope is vs the opponent's authored attack ranges. */
    private static double dodgeScopeRelevance(Move dodge, OpponentIntel intel) {
        String scope = dodge.getDodgeScope() == null ? "BOTH" : dodge.getDodgeScope().trim().toUpperCase();
        boolean oppHasMelee = false;
        boolean oppHasRanged = false;
        for (Move attack : intel.attacks) {
            if (attack.isMelee()) oppHasMelee = true;
            if (attack.isRanged()) oppHasRanged = true;
        }
        return switch (scope) {
            case "MELEE"  -> oppHasMelee ? 1.0 : 0.2;
            case "RANGED" -> oppHasRanged ? 1.0 : 0.2;
            default       -> 1.0; // BOTH
        };
    }

    /**
     * Counterplay discount when the opponent owns moves that bypass this
     * defense: guard-break bypasses blocks; intangible bypasses blocks and
     * parries. Dodges are never bypassed.
     */
    static double defenseCounterplayMultiplier(Move defense, OpponentIntel intel) {
        if (intel.hasGuardBreak && defense.isBlock()) return GUARDBREAK_BLOCK_PENALTY;
        if (intel.hasIntangible && (defense.isBlock() || defense.isParry())) {
            return INTANGIBLE_DEFENSE_PENALTY;
        }
        return 1.0;
    }

    // -------------------------------------------------------------------------
    // Weighted-random selection
    // -------------------------------------------------------------------------

    /**
     * Pick one move by weighted-random over the supplied weights (parallel
     * lists). Weights are floored at {@link #MIN_WEIGHT} so a usable candidate
     * is never impossible to pick.
     */
    static Move weightedRandomPick(List<Move> pool, List<Double> weights, RandomSource rng) {
        if (pool.isEmpty()) return null;
        double total = 0;
        for (int i = 0; i < pool.size(); i++) {
            total += Math.max(MIN_WEIGHT, weights.get(i));
        }
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            roll -= Math.max(MIN_WEIGHT, weights.get(i));
            if (roll <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    // -------------------------------------------------------------------------
    // Placement helpers
    // -------------------------------------------------------------------------

    /** The latest end-tick currently occupied on a board (0 if empty). */
    static int lastEndTick(BattlePlan plan, Move move) {
        int last = 0;
        for (ActionSegment s : plan.boardTimeline(BattlePlan.boardFor(move)).getSegments()) {
            last = Math.max(last, s.getEndTick());
        }
        return last;
    }

    /**
     * Place bunched at or after {@code nearTick}: the first free start tick from
     * {@code nearTick} upward that fits the move on its assigned board.
     */
    static ActionSegment placeAtOrAfter(BattlePlan plan, Move move, int ceCost, int nearTick) {
        Timeline board = plan.boardTimeline(BattlePlan.boardFor(move));
        int grid = board.getGridLength();
        int need = move.getApCost();
        for (int start = Math.max(1, nearTick); start + need - 1 <= grid; start++) {
            if (board.isRangeFree(start, start + need - 1)) {
                return plan.place(move, start, ceCost);
            }
        }
        return null;
    }

    /**
     * Place at a random free start tick; after a bounded number of attempts
     * fall back to first-fit (so the AP still gets spent).
     */
    static ActionSegment placeAtFreeRandom(
        BattlePlan plan, Move move, int ceCost, int gridLength, RandomSource rng
    ) {
        Timeline board = plan.boardTimeline(BattlePlan.boardFor(move));
        int need = move.getApCost();
        if (need > gridLength) return null;
        for (int attempt = 0; attempt < RANDOM_PLACE_TRIES; attempt++) {
            int start = 1 + rng.nextInt(gridLength - need + 1);
            if (board.isRangeFree(start, start + need - 1)) {
                return plan.place(move, start, ceCost);
            }
        }
        return plan.placeFirstFit(move, ceCost);
    }

    /**
     * Place a move bunched against the end of the grid by scanning backward for
     * the latest free slot that fits it.
     */
    static ActionSegment placeBunchedAtEnd(BattlePlan plan, Move move, int ceCost, int gridLength) {
        Timeline board = plan.boardTimeline(BattlePlan.boardFor(move));
        int need = move.getApCost();
        for (int start = gridLength - need + 1; start >= 1; start--) {
            if (board.isRangeFree(start, start + need - 1)) {
                return plan.place(move, start, ceCost);
            }
        }
        return null;
    }

    /**
     * Align a defensive move's fire-tick to a committed opponent attack's
     * fire-tick, so its window covers the incoming attack. A same-tick defense
     * only contests if it fires first, which requires the AI to be at least as
     * fast as the opponent (defenses are skipped until they've fired). Returns
     * null when the AI is too slow or the alignment won't fit.
     */
    static ActionSegment placeAlignedToThreat(
        BattlePlan plan, Move defense, int ceCost, int threatFireTick,
        BattleCombatant ai, BattleCombatant opponent
    ) {
        if (opponent != null
            && ai.getEffectiveStats().getSpeed() < opponent.getEffectiveStats().getSpeed()) {
            return null;
        }
        int start = Math.max(1, threatFireTick - defense.getUnleashPoint() + 1);
        return plan.place(defense, start, ceCost);
    }
}
