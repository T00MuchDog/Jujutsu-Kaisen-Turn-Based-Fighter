package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;

import java.util.Random;

/**
 * Computes final damage dealt by a move.
 *
 * Pipeline:
 *  1. Hit roll   — does the move connect?
 *  2. Power      — attacker's Power for this move category (via PowerCalculator)
 *  3. Defense    — defender's current Defense (via CombatStats.computeDefense)
 *  4. Damage     — scaled formula applied
 *  5. Black Flash roll — if eligible and move hits
 *  6. BF multiplier applied if proc'd
 *
 * Damage formula:
 *   damage = basePower × (power / defense) × DAMAGE_SCALE × roll
 *
 * DAMAGE_SCALE = 0.5 (PLACEHOLDER — tune during balance pass).
 *
 * Design targets at baseline stats (power≈80, defense≈80):
 *   Basic Punch  (basePower=30):  ~13–15 dmg  on a 200 HP target = ~7%
 *   Heavy Punch  (basePower=65):  ~28–32 dmg  on a 200 HP target = ~15%
 *   Cleave       (basePower=110): ~48–56 dmg  on a 200 HP target = ~26%
 *   Sukuna Cleave vs Yuji (power≈294, defense≈172): ~83–97 dmg on 467 HP = ~19%
 *
 * All randomness uses an injected Random for testability.
 */
public final class DamageCalculator {

    /**
     * PLACEHOLDER: global damage scale factor.
     * Lower = less damage per hit, longer fights.
     * Higher = more damage, faster fights.
     * Target: even matchup fights last 4–6 rounds with 2–4 moves per round.
     */
    private static final double DAMAGE_SCALE = 0.5;

    /** Low end of the random damage roll (±15% variance). */
    private static final double ROLL_MIN     = 0.85;

    private DamageCalculator() {}

    /**
     * Full damage resolution for a single move landing on a target.
     *
     * @param attacker      attacking combatant
     * @param defender      defending combatant
     * @param move          the move being executed
     * @param currentTick   current AP tick (for dynamic defense calculation)
     * @param rng           random source
     * @param currentRound  the current round number (for BFS logic)
     * @return              a DamageResult containing all calculated values
     */
    public static DamageResult resolve(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        int             currentTick,
        Random          rng,
        int             currentRound
    ) {
        // Use ability-modified stats for all calculations
        CharacterStats acs = attacker.getEffectiveStats();

        // --- 1. Hit roll ---
        boolean hit;
        if (move.isNeverMiss()) {
            hit = true;
        } else {
            double hitChance = CombatStats.computeHitChance(
                attacker.getEffectiveCombatStats().getAccuracy(),
                defender.getEffectiveCombatStats().getEvasion(),
                move.getBaseAccuracy()
            );
            hit = rng.nextDouble() < hitChance;
        }

        if (!hit) {
            return DamageResult.miss(move);
        }

        // --- 2. Check block ---
        Timeline defTimeline = defender.getTimeline();
        MoveBlock activeBlock = defTimeline != null ? defTimeline.blockAt(currentTick) : null;
        // Only count non-knocked-out defensive blocks
        if (activeBlock != null && (activeBlock.isKnockedOut()
                || activeBlock.getMove().getDefenseType() == DefenseType.NONE)) {
            activeBlock = null;
        }

        if (activeBlock != null) {
            DefenseType dt = activeBlock.getMove().getDefenseType();
            if (dt == DefenseType.PERCENTAGE_BLOCK && activeBlock.getMove().getBlockDamageReduction() >= 100) {
                return DamageResult.blocked(move);
            }
        }

        // --- 3. Power ---
        int power   = PowerCalculator.compute(move.getCategory(), acs);

        // --- 4. Defense ---
        int defense = defender.computeCurrentDefense(currentTick);
        if (defense < 1) defense = 1; // prevent division-by-zero or negative defense

        // --- 5. Damage formula ---
        // damage = basePower × (power / defense) × DAMAGE_SCALE × roll
        // If a block is active, apply reduction (percentage or flat) after the formula.
        double randomRoll = ROLL_MIN + (1.0 - ROLL_MIN) * rng.nextDouble();
        int rawDamage = (int) Math.round(
            move.getBasePower() * ((double) power / defense) * DAMAGE_SCALE * randomRoll
        );
        rawDamage = Math.max(1, rawDamage);

        if (activeBlock != null) {
            DefenseType dt = activeBlock.getMove().getDefenseType();
            if (dt == DefenseType.PERCENTAGE_BLOCK) {
                int reductionPct = activeBlock.getMove().getBlockDamageReduction();
                rawDamage = (int) Math.round(rawDamage * (100 - reductionPct) / 100.0);
                rawDamage = Math.max(1, rawDamage);
            } else if (dt == DefenseType.FLAT_BLOCK) {
                rawDamage = Math.max(1, rawDamage - activeBlock.getMove().getBlockFlatReduction());
            }
        }

        // --- 6. Black Flash roll ---
        boolean blackFlash = false;
        int finalDamage    = rawDamage;

        if (move.isBlackFlashEligible()) {
            double bfChance = attacker.getCurrentBfChance();
            blackFlash      = rng.nextDouble() < bfChance;

            if (blackFlash) {
                finalDamage = (int) Math.round(rawDamage * CombatStats.BF_DAMAGE_MULTIPLIER);
                // CE restore and BFS state update handled by CombatResolver after receiving result
            }
        }

        return DamageResult.hit(move, finalDamage, rawDamage, blackFlash);
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    public static class DamageResult {

        public enum Outcome { MISS, BLOCKED, HIT }

        private final Outcome outcome;
        private final Move    move;
        private final int     finalDamage;
        private final int     rawDamage;       // before BF multiplier
        private final boolean blackFlash;

        private DamageResult(Outcome outcome, Move move, int finalDamage, int rawDamage, boolean blackFlash) {
            this.outcome     = outcome;
            this.move        = move;
            this.finalDamage = finalDamage;
            this.rawDamage   = rawDamage;
            this.blackFlash  = blackFlash;
        }

        public static DamageResult miss(Move move)    { return new DamageResult(Outcome.MISS,    move, 0, 0, false); }
        public static DamageResult blocked(Move move) { return new DamageResult(Outcome.BLOCKED, move, 0, 0, false); }
        public static DamageResult hit(Move move, int finalDmg, int rawDmg, boolean bf) {
            return new DamageResult(Outcome.HIT, move, finalDmg, rawDmg, bf);
        }

        public Outcome getOutcome()     { return outcome; }
        public Move    getMove()        { return move; }
        public int     getFinalDamage() { return finalDamage; }
        public int     getRawDamage()   { return rawDamage; }
        public boolean isBlackFlash()   { return blackFlash; }
        public boolean isHit()          { return outcome == Outcome.HIT; }
        public boolean isMiss()         { return outcome == Outcome.MISS; }
        public boolean isBlocked()      { return outcome == Outcome.BLOCKED; }
    }
}
