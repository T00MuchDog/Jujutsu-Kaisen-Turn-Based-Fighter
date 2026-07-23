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
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for move self-effects on damaging moves.
 *
 * Previously the combat engine only applied {@code selfEffects} for defensive
 * and utility moves; a self-buff set on a damaging attack was silently
 * discarded. The resolver now applies self-effects on unleash for every move
 * type — these tests lock that behaviour in.
 *
 * Drives the full CombatResolver via resolveRound.
 */
public class SelfEffectsTest {

    /**
     * Headline case: a damaging PHYSICAL attack carrying a Strength increase.
     * On unleash the attacker should gain the effect, verified both via the
     * combatant's active-effect list and the STATUS_APPLIED combat event.
     */
    @Test
    void damagingMoveAppliesSelfEffectOnUnleash() {
        Move selfBuffAttack = new Move.Builder("BUFF_STRIKE")
            .name("Buff Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .selfEffects(List.of(new StatusEffect(
                StatusEffectType.STRENGTH_INCREASE, 3, 10.0)))
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
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(selfBuffAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(defenderMove));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        attackerTimeline.placeAt(selfBuffAttack, 1, 0);
        Timeline defenderTimeline = new Timeline(30);
        defenderTimeline.placeAt(defenderMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        assertTrue(attacker.hasEffect(StatusEffectType.STRENGTH_INCREASE),
            "Attacker should have a Strength increase after unleashing the move.");
        assertEquals(90, attacker.getEffectiveStats().getStrength());

        // A STATUS_APPLIED event for the self-buff was emitted, sourced on the attacker.
        CombatEvent selfBuffEvent = events.stream()
            .filter(e -> e.getType() == CombatEvent.Type.STATUS_APPLIED)
            .filter(e -> "Attacker".equals(e.getSource().getCharacter().getName()))
            .filter(e -> e.getMessage() != null && e.getMessage().contains("Increase Strength"))
            .findFirst().orElse(null);
        assertNotNull(selfBuffEvent,
            "A STATUS_APPLIED event for the attacker's Strength increase should be emitted.");

        // The self-buff should NOT have leaked onto the defender.
        assertFalse(defender.hasEffect(StatusEffectType.STRENGTH_INCREASE),
            "Defender should NOT receive the attacker's self-effect.");
    }

    /**
     * Self-effects fire on unleash regardless of whether the attack lands: a
     * self-buffing move that STILL misses applies its self-effect anyway. This
     * matches the documented "applies on unleash" timing.
     */
    @Test
    void selfEffectAppliesEvenWhenAttackMisses() {
        Move selfBuffAttack = new Move.Builder("BUFF_STRIKE")
            .name("Buff Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(false)
            .baseAccuracy(1.0)
            .apCost(10)
            .unleashPoint(1)
            .selfEffects(List.of(new StatusEffect(
                StatusEffectType.STRENGTH_INCREASE, 3, 10.0)))
            .build();

        CharacterStats attackerStats = new CharacterStats.Builder().speed(120).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(80).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(selfBuffAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of());
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        attackerTimeline.placeAt(selfBuffAttack, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(new Timeline(30));

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        // FixedRandom.nextDouble() == 1.0 is >= any hitChance < 1.0, so the attack misses.
        new CombatResolver(new FixedRandom(1.0)).resolveRound(state);

        assertTrue(attacker.hasEffect(StatusEffectType.STRENGTH_INCREASE),
            "Self-effect should apply on unleash even when the attack misses.");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit/miss rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
