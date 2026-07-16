package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.move.Move;

import java.util.Random;

/**
 * Computes final damage dealt by a move.
 *
 * Pipeline:
 *  1. Hit roll   — does the move connect?
 *  2. Power      — attacker's Power for this move category (via PowerCalculator)
 *  3. Block      — defensive move reduction applies to basePower × Power
 *  4. Defense    — defender's current Defense (via CombatStats.computeDefense)
 *  5. Damage     — scaled formula applied
 *  5. Black Flash roll — if eligible and move hits
 *  6. BF multiplier applied if proc'd
 *
 * Damage formula:
 *   damage = ((basePower × power) after block / defense) × DAMAGE_SCALE × roll
 *
 * DAMAGE_SCALE = 0.5 (PLACEHOLDER — tune during balance pass).
 *
 * Design targets at baseline stats (power≈80, defense≈80):
 *   Basic Punch  (basePower=30):  ~13–15 dmg  on a 200 HP target = ~7%
 *   Heavy Punch  (basePower=65):  ~28–32 dmg  on a 200 HP target = ~15%
 *   Cleave       (basePower=110): ~48–56 dmg  on a 200 HP target = ~26%
 *   Sukuna Cleave vs Yuji (power≈294, defense≈172): ~83–97 dmg on 467 HP = ~19%
 *
 * All randomness uses an injected {@link RandomSource} for testability and
 * deterministic authoritative resolution.
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
        RandomSource    rng,
        int             currentRound
    ) {
        // Use ability-modified stats for all calculations
        CharacterStats acs = attacker.getEffectiveStats();

        // --- 1. Hit roll ---
        boolean hit;
        if (move.isNeverMiss()) {
            hit = true;
        } else {
            double modifiedAccuracy = (attacker.getEffectiveCombatStats().getAccuracy()
                + attacker.getAbilityFlags().accuracyBonusFor(move)
                + defender.getAbilityFlags().opponentAccuracyBonusFor(move))
                * attacker.getAbilityFlags().accuracyMultiplierFor(move)
                * defender.getAbilityFlags().opponentAccuracyMultiplierFor(move);
            int attackerAccuracy = (int) Math.round(Math.max(0, modifiedAccuracy));
            double hitChance = CombatStats.computeHitChance(
                attackerAccuracy,
                defender.getEffectiveCombatStats().getEvasion(),
                Math.min(1.0, Math.max(0.0,
                    move.getBaseAccuracy() + attacker.getStatusBaseAccuracyBonus()))
            );
            hit = rng.nextDouble() < hitChance;
        }

        if (!hit) {
            return DamageResult.miss(move);
        }

        // --- 2. Check block ---
        // A GUARD_BREAK move ignores blocking defensive moves (PERCENTAGE_BLOCK /
        // FLAT_BLOCK). Dodges and parries are unaffected; only blocks are bypassed.
        Timeline defTimeline = defender.getTimeline();
        ActionSegment activeBlockSegment = (!move.isGuardBreak() && defTimeline != null)
            ? defTimeline.activeBlockAt(currentTick, move) : null;

        // --- 3. Power ---
        int power   = PowerCalculator.compute(move.getCategory(), acs);

        // --- 4. Apply defensive block before Defense ---
        double attackValue = move.getBasePower() * (double) power;
        if (activeBlockSegment != null) {
            attackValue = activeBlockSegment.getMove().applyBlockTo(attackValue);
            if (attackValue == 0) {
                return DamageResult.blocked(move); // full block
            }
        }

        // --- 5. Defense ---
        int defense = defender.computeCurrentDefense(currentTick);
        if (defense < 1) defense = 1; // prevent division-by-zero or negative defense

        // --- 6. Damage formula ---
        // damage = ((basePower × power) after block / defense) × DAMAGE_SCALE × roll
        double randomRoll = ROLL_MIN + (1.0 - ROLL_MIN) * rng.nextDouble();
        int rawDamage = (int) Math.round(
            (attackValue / defense) * DAMAGE_SCALE * randomRoll
                * attacker.getAbilityFlags().damageMultiplierFor(move)
        );
        rawDamage = Math.max(1, rawDamage);

        // --- 7. Black Flash roll ---
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

    /**
     * Compatibility overload for callers that still supply {@link Random}.
     */
    public static DamageResult resolve(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        int             currentTick,
        Random          rng,
        int             currentRound
    ) {
        return resolve(
            attacker,
            defender,
            move,
            currentTick,
            new SeededRandomSource(rng),
            currentRound
        );
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
