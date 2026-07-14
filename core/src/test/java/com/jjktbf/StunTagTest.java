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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the STUN move tag: on a successful hit, the defender's action
 * segment(s) on the current tick are stunned (removed from the timeline and
 * prevented from firing).
 *
 * Drives the full CombatResolver via resolveRound, asserting on the returned
 * CombatEvents and the resulting segment state.
 */
public class StunTagTest {

    /**
     * The headline case: attacker has a STUN-tagged instant move that fires
     * first (higher speed). Defender has an instant move firing the same tick.
     * On hit, the defender's segment is stunned and never fires — the log reads
     * "<defender> was stunned and could not move."
     */
    @Test
    void stunTagOnHitStunsDefenderSegmentAndPreventsItFiring() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .stun(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move defenderMove = new Move.Builder("DEFENDER_MOVE")
            .name("Defender Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        // Attacker is faster so its instant move resolves first this tick.
        CharacterStats attackerStats = new CharacterStats.Builder().speed(120).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(80).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(stunAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(defenderMove));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        ActionSegmentRef attackerSeg = new ActionSegmentRef(attackerTimeline.placeAt(stunAttack, 1, 0));
        ActionSegmentRef defenderSeg = new ActionSegmentRef(null);
        Timeline defenderTimeline = new Timeline(30);
        defenderSeg.segment = defenderTimeline.placeAt(defenderMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        // The defender's segment was stunned.
        assertTrue(defenderSeg.segment.isStunned(),
            "Defender's segment should be stunned after a STUN-tagged hit.");

        // A MOVE_STUNNED event was emitted with the stun wording.
        CombatEvent stunEvent = events.stream()
            .filter(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED)
            .findFirst().orElse(null);
        assertNotNull(stunEvent, "A MOVE_STUNNED event should be emitted.");
        assertEquals("Defender", stunEvent.getTarget().getCharacter().getName());
        assertTrue(stunEvent.getMessage().contains("was stunned and could not move"),
            "Unexpected stun message: " + stunEvent.getMessage());

        // The attacker's move fired; the defender's move did NOT fire.
        boolean attackerFired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                        && "Stun Strike".equals(e.getMove().getName()));
        boolean defenderFired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                        && "Defender Strike".equals(e.getMove().getName()));
        assertTrue(attackerFired, "Attacker's STUN move should have fired.");
        assertFalse(defenderFired, "Defender's move should NOT have fired (it was stunned).");

        // Sanity: the attacker's own segment was not stunned.
        assertFalse(attackerSeg.segment.isStunned(),
            "Attacker's segment should not be stunned.");
    }

    /**
     * A miss does not stun: the hit roll fails, so the stun effect never applies,
     * and the defender's segment is neither stunned nor prevented from firing.
     */
    @Test
    void stunTagOnMissDoesNotStun() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .baseAccuracy(0.0)          // force a miss
            .stun(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move defenderMove = new Move.Builder("DEFENDER_MOVE")
            .name("Defender Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats attackerStats = new CharacterStats.Builder().speed(120).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(80).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(stunAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(defenderMove));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        ActionSegmentRef defenderSeg = new ActionSegmentRef(null);
        Timeline defenderTimeline = new Timeline(30);
        defenderSeg.segment = defenderTimeline.placeAt(defenderMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        attackerTimeline.placeAt(stunAttack, 1, 0);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        // FixedRandom.nextDouble() == 1.0 is >= any hitChance < 1.0, so the attack misses.
        List<CombatEvent> events = new CombatResolver(new FixedRandom(1.0)).resolveRound(state);

        assertFalse(defenderSeg.segment.isStunned(),
            "Defender's segment should NOT be stunned when the STUN move misses.");

        boolean anyStunEvent = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED);
        assertFalse(anyStunEvent, "No MOVE_STUNNED event should be emitted on a miss.");

        boolean defenderFired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                        && "Defender Strike".equals(e.getMove().getName()));
        assertTrue(defenderFired, "Defender's move should still fire when the STUN move missed.");
    }

    /**
     * A hit from a move WITHOUT the stun tag does not stun the defender.
     */
    @Test
    void nonStunHitDoesNotStun() {
        Move plainAttack = new Move.Builder("PLAIN_ATTACK")
            .name("Plain Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            // no .stun(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move defenderMove = new Move.Builder("DEFENDER_MOVE")
            .name("Defender Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats attackerStats = new CharacterStats.Builder().speed(120).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(80).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(plainAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(defenderMove));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        ActionSegmentRef defenderSeg = new ActionSegmentRef(null);
        Timeline defenderTimeline = new Timeline(30);
        defenderSeg.segment = defenderTimeline.placeAt(defenderMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        attackerTimeline.placeAt(plainAttack, 1, 0);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        assertFalse(defenderSeg.segment.isStunned(),
            "Defender's segment should NOT be stunned by a non-STUN move.");
        boolean anyStunEvent = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED);
        assertFalse(anyStunEvent, "No MOVE_STUNNED event should be emitted without the STUN tag.");
    }

    /**
     * The STUN tag survives the MoveData ↔ Move round-trip (it is stored as a
     * dedicated flag, not derived from the category).
     */
    @Test
    void stunTagSurvivesMoveDataRoundTrip() {
        Move original = new Move.Builder("ROUND_TRIP")
            .name("Round Trip Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .stun(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        com.jjktbf.model.move.MoveData dto = com.jjktbf.model.move.MoveData.fromMove(original);
        assertTrue(dto.stun, "DTO should carry the stun flag after fromMove().");

        Move restored = dto.toMove();
        assertTrue(restored.isStun(), "Restored Move should have isStun() true after toMove().");
        assertTrue(restored.hasTag("STUN"), "Restored Move should hasTag(\"STUN\").");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit/miss rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }

    /** Tiny mutable holder so we can keep a reference to a placed segment for assertions. */
    private static final class ActionSegmentRef {
        com.jjktbf.model.combat.ActionSegment segment;
        ActionSegmentRef(com.jjktbf.model.combat.ActionSegment segment) { this.segment = segment; }
    }
}
