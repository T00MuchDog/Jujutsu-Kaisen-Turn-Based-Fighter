package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.coded.CodedHitModifiers;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;

import java.util.List;
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
 * Power (and Defense) are computed from StatScale-scaled stats (see PowerCalculator
 * and CombatStats). PHYSICAL-category Power is then multiplied by
 * PHYSICAL_POWER_MULTIPLIER (< 1.0) — physical moves are weaker than CE/technique
 * moves at equal base power, so a physical Power edge cannot two-hit a peer.
 *
 * DAMAGE_SCALE = 0.42 (tuned for longer fights; was 0.5).
 *
 * All randomness uses an injected {@link RandomSource} for testability and
 * deterministic authoritative resolution.
 */
public final class DamageCalculator {

    /**
     * Global damage scale factor.
     * Lower = less damage per hit, longer fights.
     * Higher = more damage, faster fights.
     * Target: even matchup fights last 4–6 rounds with 2–4 moves per round.
     */
    private static final double DAMAGE_SCALE = 0.42;

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
        return resolve(attacker, defender, move, firstComponent(move),
            currentTick, rng, currentRound, false);
    }

    public static DamageResult resolve(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        int             currentTick,
        RandomSource    rng,
        int             currentRound,
        boolean         forceFullBlock
    ) {
        return resolve(attacker, defender, move, firstComponent(move),
            currentTick, rng, currentRound, forceFullBlock);
    }

    public static DamageResult resolve(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        HitComponent    component,
        int             currentTick,
        RandomSource    rng,
        int             currentRound
    ) {
        return resolve(attacker, defender, move, component,
            currentTick, rng, currentRound, false);
    }

    /** Resolve one authored hit component while inheriting move-level behavior. */
    public static DamageResult resolve(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        HitComponent    component,
        int             currentTick,
        RandomSource    rng,
        int             currentRound,
        boolean         forceFullBlock
    ) {
        return resolve(attacker, defender, move, component, currentTick, rng,
            currentRound, forceFullBlock, false);
    }

    /** Resolve one component with optional exclusion of defenses not yet unleashed. */
    public static DamageResult resolve(
        BattleCombatant attacker,
        BattleCombatant defender,
        Move            move,
        HitComponent    component,
        int             currentTick,
        RandomSource    rng,
        int             currentRound,
        boolean         forceFullBlock,
        boolean         requireFiredDefense
    ) {
        if (component == null) throw new IllegalArgumentException("hit component is required");
        // Use ability-modified stats for all calculations
        CharacterStats acs = attacker.getEffectiveStats();

        Timeline defTimeline = defender.getTimeline();

        // --- 0. Dodge roll (chance-based, no potency gate) ---
        // A live DODGE defense reacts to a scope-matching incoming attack with a
        // dodgeChance% probability of avoiding it entirely. (Future AOE bypasses.)
        if (component.isAvoidable() && defTimeline != null) {
            ActionSegment dodgeSeg = defTimeline.activeDefenseAt(
                currentTick, move, component, com.jjktbf.model.move.DefenseType.DODGE,
                requireFiredDefense);
            if (dodgeSeg != null) {
                int chance = Math.max(0, Math.min(100, dodgeSeg.getMove().getDodgeChance()));
                if (chance >= 100 || rng.nextDouble() < chance / 100.0) {
                    return DamageResult.dodged(move, component, dodgeSeg, List.of());
                }
            }
        }

        // --- 1. Hit roll ---
        if (component.isAvoidable()) {
            boolean hit;
            if (defender.consumeGuaranteedDodge()) {
                hit = false;
            } else if (move.isNeverMiss() || attacker.consumeGuaranteedHit()) {
                hit = true;
            } else {
                // Each component may define its own base accuracy; otherwise the
                // move's base accuracy applies (legacy single-hit behaviour).
                double componentAccuracy = component.hasOwnAccuracy()
                    ? component.getBaseAccuracy()
                    : move.getBaseAccuracy();
                double modifiedAccuracy = (attacker.getAccuracy()
                    + attacker.getAbilityFlags().accuracyBonusFor(move)
                    + defender.getAbilityFlags().opponentAccuracyBonusFor(move))
                    * attacker.getAbilityFlags().accuracyMultiplierFor(move)
                    * defender.getAbilityFlags().opponentAccuracyMultiplierFor(move);
                int attackerAccuracy = (int) Math.round(Math.max(0, modifiedAccuracy));
                double hitChance = CombatStats.computeHitChance(
                    attackerAccuracy,
                    defender.getEvasion(),
                    componentAccuracy
                );
                hit = rng.nextDouble() < hitChance;
            }

            if (!hit) {
                return DamageResult.miss(move, component);
            }
        }

        // Compiled techniques can react to a direct connection before block and
        // defense are calculated. Misses never reach this hook.
        CodedHitModifiers codedModifiers = attacker.getCodedAbilities().onAttackConnected(
            attacker, defender, move, component, currentTick, rng);

        // --- 1b. Parry check (potency-gated; GUARD_BREAK does NOT bypass parry) ---
        // A parry negates the hit entirely. If the parry would stagger the attacker
        // (non-GUARD_BREAK, parryStaggerTicks > 0), the resolver applies the stagger.
        if (component.isAvoidable() && defTimeline != null) {
            ActionSegment parrySeg = defTimeline.activeDefenseAt(
                currentTick, move, component, com.jjktbf.model.move.DefenseType.PARRY,
                requireFiredDefense);
            if (parrySeg != null && parrySeg.getMove().getPotency() >= move.getPotency()) {
                boolean stagger = parrySeg.getMove().parryStaggersAttacker(move);
                int staggerTicks = stagger ? parrySeg.getMove().getParryStaggerTicks() : 0;
                return DamageResult.parried(
                    move, component, parrySeg, staggerTicks, codedModifiers.events());
            }
        }

        if (forceFullBlock && !move.isGuardBreak()) {
            return DamageResult.blocked(move, component, null, codedModifiers.events());
        }
        boolean bypassBlock = move.isGuardBreak() || codedModifiers.bypassBlock();

        // --- 2. Check block ---
        // A GUARD_BREAK move ignores blocking defensive moves (BLOCK). Dodges and
        // parries are unaffected; only blocks are bypassed. Blocks are potency-gated:
        // a block only applies when block.potency >= attack.potency.
        ActionSegment activeBlockSegment = null;
        if (!bypassBlock && defTimeline != null) {
            ActionSegment blk = defTimeline.activeDefenseAt(
                currentTick, move, component, com.jjktbf.model.move.DefenseType.BLOCK,
                requireFiredDefense);
            if (blk != null && blk.getMove().getPotency() >= move.getPotency()) {
                activeBlockSegment = blk;
            }
        }

        // --- 3. Power ---
        // PHYSICAL-category Power is dampened (PHYSICAL_POWER_MULTIPLIER < 1.0):
        // physical moves are weaker than CE/technique moves at equal base power.
        // The multiplier applies to the raw PowerCalculator output before
        // POWER battle-stat modifiers, so ability Power buffs compose on top.
        double power = PowerCalculator.compute(component.getCategory(), acs);
        if (component.getCategory() == MoveCategory.PHYSICAL) {
            power *= CombatStats.PHYSICAL_POWER_MULTIPLIER;
        }
        power = Math.max(0.0, attacker.modifyBattleStat(
            com.jjktbf.model.character.BattleStatKey.POWER, power));

        // --- 4. Apply defensive block before Defense ---
        double attackValue = component.getBasePower()
            * attacker.getAbilityFlags().basePowerMultiplierFor(move)
            * power;
        if (activeBlockSegment != null) {
            attackValue = activeBlockSegment.getMove().applyBlockTo(attackValue);
            if (attackValue == 0) {
                return DamageResult.blocked(
                    move, component, activeBlockSegment, codedModifiers.events());
            }
        }

        // --- 5. Defense ---
        double defense = Math.max(1.0,
            defender.computeCurrentDefense(currentTick) * codedModifiers.defenseMultiplier());

        // --- 6. Damage formula ---
        // damage = ((basePower × power) after block / defense) × DAMAGE_SCALE × roll
        double randomRoll = ROLL_MIN + (1.0 - ROLL_MIN) * rng.nextDouble();
        int rawDamage = (int) Math.round(
            (attackValue / defense) * DAMAGE_SCALE * randomRoll
                * attacker.getAbilityFlags().damageMultiplierFor(move)
        );
        rawDamage = Math.max(1, rawDamage);
        rawDamage = Math.max(0, (int) Math.round(
            attacker.modifyBattleStat(com.jjktbf.model.character.BattleStatKey.DAMAGE_DEALT, rawDamage)));

        // --- 7. Black Flash roll ---
        boolean blackFlash = false;
        int finalDamage    = rawDamage;

        if (component.isBlackFlashEligible()) {
            double bfChance = attacker.getCurrentBfChance();
            blackFlash = attacker.consumeGuaranteedBlackFlash() || rng.nextDouble() < bfChance;

            if (blackFlash) {
                finalDamage = (int) Math.round(rawDamage * CombatStats.BF_DAMAGE_MULTIPLIER);
                // CE restore and BFS state update handled by CombatResolver after receiving result
            }
        }

        return DamageResult.hit(move, component, finalDamage, rawDamage, blackFlash,
            bypassBlock, codedModifiers.events(), activeBlockSegment);
    }

    private static HitComponent firstComponent(Move move) {
        if (move == null || move.getHitComponents().isEmpty()) {
            throw new IllegalArgumentException("damaging move must have at least one hit component");
        }
        return move.getHitComponents().get(0);
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
            currentRound,
            false
        );
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    public static class DamageResult {

        public enum Outcome { MISS, BLOCKED, HIT, DODGED, PARRIED }

        private final Outcome outcome;
        private final Move    move;
        private final HitComponent component;
        private final int     finalDamage;
        private final int     rawDamage;       // before BF multiplier
        private final boolean blackFlash;
        private final boolean bypassedBlock;
        private final List<CombatEvent> codedEvents;
        /** The defender's defense segment that resolved this result (block/parry/dodge), if any. */
        private final ActionSegment defenseSegment;
        /** For a PARRIED result: ticks to stagger the attacker (0 = no stagger). */
        private final int parryStaggerTicks;

        private DamageResult(
            Outcome outcome,
            Move move,
            HitComponent component,
            int finalDamage,
            int rawDamage,
            boolean blackFlash,
            boolean bypassedBlock,
            List<CombatEvent> codedEvents,
            ActionSegment defenseSegment,
            int parryStaggerTicks
        ) {
            this.outcome     = outcome;
            this.move        = move;
            this.component   = component;
            this.finalDamage = finalDamage;
            this.rawDamage   = rawDamage;
            this.blackFlash  = blackFlash;
            this.bypassedBlock = bypassedBlock;
            this.codedEvents = codedEvents == null ? List.of() : List.copyOf(codedEvents);
            this.defenseSegment = defenseSegment;
            this.parryStaggerTicks = parryStaggerTicks;
        }

        public static DamageResult miss(Move move) {
            return miss(move, firstComponent(move));
        }
        public static DamageResult miss(Move move, HitComponent component) {
            return new DamageResult(
                Outcome.MISS, move, component, 0, 0, false, false, List.of(), null, 0);
        }
        public static DamageResult blocked(Move move, List<CombatEvent> codedEvents) {
            return blocked(move, firstComponent(move), null, codedEvents);
        }
        public static DamageResult blocked(
            Move move,
            HitComponent component,
            ActionSegment defenseSegment,
            List<CombatEvent> codedEvents
        ) {
            return new DamageResult(
                Outcome.BLOCKED, move, component, 0, 0, false, false,
                codedEvents, defenseSegment, 0);
        }
        /** Dodge outcome — the defender avoided the attack entirely. */
        public static DamageResult dodged(Move move, ActionSegment dodgeSegment,
                                           List<CombatEvent> codedEvents) {
            return dodged(move, firstComponent(move), dodgeSegment, codedEvents);
        }
        public static DamageResult dodged(
            Move move,
            HitComponent component,
            ActionSegment dodgeSegment,
            List<CombatEvent> codedEvents
        ) {
            return new DamageResult(Outcome.DODGED, move, component, 0, 0, false, false,
                codedEvents, dodgeSegment, 0);
        }
        /** Parry outcome — the defender negated the attack; {@code staggerTicks} stagger the attacker. */
        public static DamageResult parried(Move move, ActionSegment parrySegment,
                                            int staggerTicks, List<CombatEvent> codedEvents) {
            return parried(move, firstComponent(move), parrySegment, staggerTicks, codedEvents);
        }
        public static DamageResult parried(
            Move move,
            HitComponent component,
            ActionSegment parrySegment,
            int staggerTicks,
            List<CombatEvent> codedEvents
        ) {
            return new DamageResult(Outcome.PARRIED, move, component, 0, 0, false, false,
                codedEvents, parrySegment, staggerTicks);
        }
        public static DamageResult hit(Move move, int finalDmg, int rawDmg, boolean bf) {
            return hit(move, finalDmg, rawDmg, bf, move.isGuardBreak(), List.of());
        }
        public static DamageResult hit(
            Move move,
            int finalDmg,
            int rawDmg,
            boolean bf,
            boolean bypassedBlock,
            List<CombatEvent> codedEvents
        ) {
            return hit(move, firstComponent(move), finalDmg, rawDmg, bf,
                bypassedBlock, codedEvents, null);
        }
        public static DamageResult hit(
            Move move,
            HitComponent component,
            int finalDmg,
            int rawDmg,
            boolean bf,
            boolean bypassedBlock,
            List<CombatEvent> codedEvents,
            ActionSegment defenseSegment
        ) {
            return new DamageResult(Outcome.HIT, move, component, finalDmg, rawDmg, bf,
                bypassedBlock, codedEvents, defenseSegment, 0);
        }

        public Outcome getOutcome()     { return outcome; }
        public Move    getMove()        { return move; }
        public HitComponent getComponent() { return component; }
        public int     getFinalDamage() { return finalDamage; }
        public int     getRawDamage()   { return rawDamage; }
        public boolean isBlackFlash()   { return blackFlash; }
        public boolean bypassedBlock()  { return bypassedBlock; }
        public List<CombatEvent> getCodedEvents() { return codedEvents; }
        public ActionSegment getDefenseSegment() { return defenseSegment; }
        public int getParryStaggerTicks() { return parryStaggerTicks; }
        public boolean isHit()          { return outcome == Outcome.HIT; }
        public boolean isMiss()         { return outcome == Outcome.MISS; }
        public boolean isBlocked()      { return outcome == Outcome.BLOCKED; }
        public boolean isDodged()       { return outcome == Outcome.DODGED; }
        public boolean isParried()      { return outcome == Outcome.PARRIED; }
        /** True iff a parry should stagger the attacker (PARRIED + staggerTicks > 0). */
        public boolean staggersAttacker() {
            return outcome == Outcome.PARRIED && parryStaggerTicks > 0;
        }
    }
}
