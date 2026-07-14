package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.DamageCalculator;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GUARD_BREAK move tag: a successful hit ignores the defender's
 * blocking defensive moves (PERCENTAGE_BLOCK / FLAT_BLOCK). Dodges and parries
 * are unaffected; only blocks are bypassed.
 */
public class GuardBreakTagTest {

    /**
     * Headline case: a 100% PERCENTAGE_BLOCK would normally fully negate an
     * incoming hit (DamageOutcome.BLOCKED, 0 damage). A GUARD_BREAK attack lands
     * as a HIT with damage instead.
     */
    @Test
    void guardBreakIgnoresFullBlock() {
        Move attack = guardBreakAttack("GB_ATTACK");
        Move fullBlock = block("FULL_BLOCK", 100);

        BattleCombatant attacker = combatant("A", "Attacker", attack);
        BattleCombatant defender = combatant("D", "Defender", fullBlock);
        placeAtTick1(defender, fullBlock);

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, attack, 1, new FixedRandom(0.0), 1);

        assertTrue(result.isHit(),
            "GUARD_BREAK hit should land (not be blocked) against a 100% block.");
        assertTrue(result.getFinalDamage() > 0,
            "GUARD_BREAK hit should deal damage through a full block.");
    }

    /** Sanity: without GUARD_BREAK, the same 100% block fully negates the hit. */
    @Test
    void fullBlockNegatesNonGuardBreakHit() {
        Move attack = plainAttack("PLAIN_ATTACK");
        Move fullBlock = block("FULL_BLOCK", 100);

        BattleCombatant attacker = combatant("A", "Attacker", attack);
        BattleCombatant defender = combatant("D", "Defender", fullBlock);
        placeAtTick1(defender, fullBlock);

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, attack, 1, new FixedRandom(0.0), 1);

        assertTrue(result.isBlocked(),
            "Non-guard-break hit against a 100% block should be fully blocked.");
    }

    /**
     * A GUARD_BREAK hit against a 50% block deals the SAME damage as it would
     * with no block at all — the block is bypassed entirely.
     */
    @Test
    void guardBreakDamageEqualsUnblockedDamage() {
        Move attack = guardBreakAttack("GB_ATTACK");
        Move halfBlock = block("HALF_BLOCK", 50);

        BattleCombatant attacker = combatant("A", "Attacker", attack);

        // With the 50% block present.
        BattleCombatant blockedDefender = combatant("D", "Defender", halfBlock);
        placeAtTick1(blockedDefender, halfBlock);
        int withBlock = DamageCalculator.resolve(
            attacker, blockedDefender, attack, 1, new FixedRandom(0.0), 1).getFinalDamage();

        // With no block at all.
        BattleCombatant bareDefender = combatant("D2", "Defender2", plainAttack("FILLER"));
        int withoutBlock = DamageCalculator.resolve(
            attacker, bareDefender, attack, 1, new FixedRandom(0.0), 1).getFinalDamage();

        assertTrue(withBlock > 0, "Guard-break hit should deal damage through the block.");
        assertEquals(withoutBlock, withBlock,
            "GUARD_BREAK damage through a block should equal the unblocked damage.");
    }

    /**
     * A non-GUARD_BREAK hit against a 50% block is reduced (less than unblocked) —
     * confirms the block is actually doing something when not bypassed.
     */
    @Test
    void nonGuardBreakHitIsReducedByBlock() {
        Move attack = plainAttack("PLAIN_ATTACK");
        Move halfBlock = block("HALF_BLOCK", 50);

        BattleCombatant attacker = combatant("A", "Attacker", attack);

        BattleCombatant blockedDefender = combatant("D", "Defender", halfBlock);
        placeAtTick1(blockedDefender, halfBlock);
        int withBlock = DamageCalculator.resolve(
            attacker, blockedDefender, attack, 1, new FixedRandom(0.0), 1).getFinalDamage();

        BattleCombatant bareDefender = combatant("D2", "Defender2", plainAttack("FILLER"));
        int withoutBlock = DamageCalculator.resolve(
            attacker, bareDefender, attack, 1, new FixedRandom(0.0), 1).getFinalDamage();

        assertTrue(withBlock < withoutBlock,
            "Non-guard-break hit should be reduced by the block (got withBlock=" + withBlock
                + ", withoutBlock=" + withoutBlock + ").");
    }

    /** The GUARD_BREAK tag survives the MoveData ↔ Move round-trip. */
    @Test
    void guardBreakTagSurvivesMoveDataRoundTrip() {
        Move original = new Move.Builder("ROUND_TRIP")
            .name("Round Trip Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .neverMiss(true)
            .guardBreak(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        MoveData dto = MoveData.fromMove(original);
        assertTrue(dto.guardBreak, "DTO should carry the guardBreak flag after fromMove().");

        Move restored = dto.toMove();
        assertTrue(restored.isGuardBreak(), "Restored Move should have isGuardBreak() true.");
        assertTrue(restored.hasTag("GUARD_BREAK"), "Restored Move should hasTag(\"GUARD_BREAK\").");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Move guardBreakAttack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .guardBreak(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move plainAttack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move block(String id, int reduction) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(reduction)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static BattleCombatant combatant(String id, String name, Move move) {
        CharacterStats stats = new CharacterStats.Builder().build();
        Character c = new SorcererCharacter(id, name, stats, null, List.of(move));
        return new BattleCombatant(c);
    }

    private static void placeAtTick1(BattleCombatant defender, Move block) {
        Timeline tl = new Timeline(30);
        tl.placeAt(block, 1, 0);
        defender.setTimeline(tl);
    }

    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
    }
}
