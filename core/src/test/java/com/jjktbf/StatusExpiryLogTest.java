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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the stat-boost expiry battle-log line.
 *
 * <p>When a utility self-effect (POWER_UP, FOCUS, CE_OUTPUT_UP, …) deactivates at
 * the end of its duration, the combat engine must emit a
 * {@link CombatEvent.Type#STATUS_EXPIRED} event whose message reads like a fading
 * surge — distinct from a defensive block "dropping its guard" (covered by
 * {@link DefenseExpiryLogTest}).
 *
 * <p>The expiry is round-based (it lives on {@link StatusEffect} and fires during
 * {@link CombatResolver#processRoundEnd}), so this test drives a utility move
 * through resolution AND round-end — unlike the tick-based block expiry.
 */
public class StatusExpiryLogTest {

    /**
     * A FOCUS self-effect with durationRounds 1 applies on unleash, then expires
     * at the first round-end, emitting a per-effect "focus wavers" line.
     */
    @Test
    void statBoostExpiryIsLoggedAtRoundEnd() {
        Move focusMove = new Move.Builder("FOCUS_MOVE")
            .name("Focus Up")
            .category(MoveCategory.UTILITY)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .selfEffects(List.of(new StatusEffect(StatusEffectType.FOCUS, 1, 0.1)))
            .build();

        CharacterStats stats = new CharacterStats.Builder().speed(100).build();
        Character userChar  = new SorcererCharacter("U", "User", stats, null, List.of(focusMove));
        Character enemyChar = new SorcererCharacter("E", "Enemy", stats, null, List.of());
        BattleCombatant user  = new BattleCombatant(userChar);
        BattleCombatant enemy = new BattleCombatant(enemyChar);

        Timeline tl = new Timeline();
        tl.placeAt(focusMove, 1, 0);
        user.setTimeline(tl);
        enemy.setTimeline(new Timeline());

        BattleState state = new BattleState(user, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        CombatResolver resolver = new CombatResolver(new FixedRandom(0.0));
        List<CombatEvent> events = new ArrayList<>();
        events.addAll(resolver.resolveRound(state));
        events.addAll(resolver.processRoundEnd(state));

        // The focus was applied on unleash.
        assertTrue(user.hasEffect(StatusEffectType.FOCUS)
                || events.stream().anyMatch(e -> e.getMessage() != null
                    && e.getMessage().contains("applies FOCUS")),
            "FOCUS should be applied on unleash.");

        // It has expired by round-end.
        assertFalse(user.hasEffect(StatusEffectType.FOCUS),
            "FOCUS (durationRounds 1) should be gone after round-end.");

        // And the round-end emits a STATUS_EXPIRED event with the focus wording.
        boolean focusExpired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.STATUS_EXPIRED
                        && e.getMessage() != null
                        && e.getMessage().contains("focus wavers"));
        assertTrue(focusExpired,
            "A fading stat boost should log a STATUS_EXPIRED event with its per-effect wording.");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit/miss rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
