package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the HEAVY move tag: an action segment carrying a HEAVY move is immune
 * to being stunned by a STUN-tagged hit (it is skipped by the stun effect and still
 * fires). This is the "heavy beats stun" half of the rock-paper-scissors trio.
 *
 * Drives the full CombatResolver via resolveRound.
 */
public class HeavyTagTest {

    /**
     * Headline case: attacker fires a STUN-tagged move first (higher speed). The
     * defender has a HEAVY-tagged move firing the same tick. On hit, the HEAVY
     * segment is NOT stunned and DOES fire — no "was stunned and could not move".
     */
    @Test
    void heavyMoveIsImmuneToStunTagAndStillFires() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .stun(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move heavyMove = new Move.Builder("HEAVY_MOVE")
            .name("Heavy Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .heavy(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        // Attacker is faster so its instant move resolves first this tick.
        CharacterStats attackerStats = new CharacterStats.Builder().speed(120).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(80).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(stunAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(heavyMove));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        attackerTimeline.placeAt(stunAttack, 1, 0);
        Timeline defenderTimeline = new Timeline(30);
        com.jjktbf.model.combat.ActionSegment heavySeg =
            defenderTimeline.placeAt(heavyMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        // The defender's HEAVY segment was NOT stunned...
        assertFalse(heavySeg.isStunned(),
            "A HEAVY move's segment should not be stunned by a STUN-tagged hit.");

        // ...so no MOVE_STUNNED event should be emitted at all.
        boolean anyStunEvent = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED);
        assertFalse(anyStunEvent,
            "No MOVE_STUNNED event should be emitted when the only target is HEAVY.");

        // ...and the defender's move DID fire.
        boolean heavyFired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                        && "Heavy Strike".equals(e.getMove().getName()));
        assertTrue(heavyFired, "The HEAVY move should still fire despite the STUN hit.");
    }

    /**
     * HEAVY only resists the STUN tag — a non-HEAVY segment is still stunned as
     * usual (confirms the guard isn't accidentally skipping everything).
     */
    @Test
    void nonHeavyMoveIsStillStunned() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .stun(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move plainMove = new Move.Builder("PLAIN_MOVE")
            .name("Plain Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            // no .heavy(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats attackerStats = new CharacterStats.Builder().speed(120).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(80).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(stunAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(plainMove));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        attackerTimeline.placeAt(stunAttack, 1, 0);
        Timeline defenderTimeline = new Timeline(30);
        com.jjktbf.model.combat.ActionSegment plainSeg =
            defenderTimeline.placeAt(plainMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        assertTrue(plainSeg.isStunned(),
            "A non-HEAVY segment should still be stunned by a STUN-tagged hit.");
        boolean anyStunEvent = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED);
        assertTrue(anyStunEvent, "A MOVE_STUNNED event should be emitted for a non-HEAVY target.");
    }

    /** The HEAVY tag survives the MoveData ↔ Move round-trip. */
    @Test
    void heavyTagSurvivesMoveDataRoundTrip() {
        Move original = new Move.Builder("ROUND_TRIP")
            .name("Round Trip Heavy")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .heavy(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        MoveData dto = MoveData.fromMove(original);
        assertTrue(dto.heavy, "DTO should carry the heavy flag after fromMove().");

        Move restored = dto.toMove();
        assertTrue(restored.isHeavy(), "Restored Move should have isHeavy() true.");
        assertTrue(restored.hasTag("HEAVY"), "Restored Move should hasTag(\"HEAVY\").");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
