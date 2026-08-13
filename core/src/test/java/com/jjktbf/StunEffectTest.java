package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the STUN_CURRENT_ACTION move effect: on activation after a hit, the defender's action
 * segment(s) on the current tick are stunned (removed from the timeline and
 * prevented from firing).
 *
 * Drives the full CombatResolver via resolveRound, asserting on the returned
 * CombatEvents and the resulting segment state.
 */
public class StunEffectTest {

    /**
     * The headline case: attacker has an instant move with a stun effect that fires
     * first (higher speed). Defender has an instant move firing the same tick.
     * On hit, the defender's segment is stunned and never fires — the log reads
     * "<defender> was stunned and could not move."
     */
    @Test
    void stunEffectOnHitStunsDefenderSegmentAndPreventsItFiring() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .effects(List.of(stunEffect(1.0)))
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
            "Defender's segment should be stunned after the stun effect activates.");

        // A MOVE_STUNNED event was emitted with the stun wording.
        CombatEvent stunEvent = events.stream()
            .filter(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED)
            .findFirst().orElse(null);
        assertNotNull(stunEvent, "A MOVE_STUNNED event should be emitted.");
        assertEquals("Defender", stunEvent.getTarget().getCharacter().getName());
        assertEquals("Attacker's Stun Strike stunned Defender, who could not move.",
            stunEvent.getMessage(),
            "Unexpected stun message: " + stunEvent.getMessage());
        assertEquals("Attacker", stunEvent.getSource().getCharacter().getName());

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
    void stunEffectOnMissDoesNotStun() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .baseAccuracy(0.0)          // force a miss
            .effects(List.of(stunEffect(1.0)))
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
     * A hit from a move without the stun effect does not stun the defender.
     */
    @Test
    void hitWithoutStunEffectDoesNotStun() {
        Move plainAttack = new Move.Builder("PLAIN_ATTACK")
            .name("Plain Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
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
        assertFalse(anyStunEvent, "No MOVE_STUNNED event should be emitted without the stun effect.");
    }

    @Test
    void failedEffectChanceDoesNotStunAfterAHit() {
        Move stunAttack = new Move.Builder("CHANCE_STUN")
            .name("Chance Stun")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .effects(List.of(stunEffect(0.5)))
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
        BattleCombatant attacker = combatant("A", "Attacker", 120, stunAttack);
        BattleCombatant defender = combatant("D", "Defender", 80, defenderMove);
        Timeline attackerTimeline = new Timeline(30);
        Timeline defenderTimeline = new Timeline(30);
        attackerTimeline.placeAt(stunAttack, 1, 0);
        var defenderSegment = defenderTimeline.placeAt(defenderMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);
        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.75)).resolveRound(state);

        assertFalse(defenderSegment.isStunned());
        assertFalse(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_STUNNED));
    }

    @Test
    void legacyStunFlagMigratesToEffect() {
        MoveData data = new MoveData();
        data.id = "LEGACY_STUN";
        data.name = "Legacy Stun";
        data.tags = new java.util.ArrayList<>(List.of("PHYSICAL", "ATTACK", "MELEE", "STUN"));
        data.basePower = 10;
        data.apCost = 10;
        data.unleashPoint = 1;
        data.stun = true;

        assertTrue(data.migrateLegacyEffects());
        assertNull(data.stun);
        assertFalse(data.tags.contains("STUN"));
        assertEquals(AbilityEffectType.STUN_CURRENT_ACTION.name(), data.effects.get(0).type);
        data.toMove();
    }

    private static MoveEffectData stunEffect(double chance) {
        MoveEffectData effect = AbilityEffectType.STUN_CURRENT_ACTION.createDefaultMoveEffect();
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        effect.activationChanceEnabled = chance < 1.0;
        effect.activationChance = chance;
        return effect;
    }

    private static BattleCombatant combatant(
        String id,
        String name,
        int speed,
        Move move
    ) {
        Character character = new SorcererCharacter(
            id, name, new CharacterStats.Builder().speed(speed).build(), null, List.of(move));
        return new BattleCombatant(character);
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
