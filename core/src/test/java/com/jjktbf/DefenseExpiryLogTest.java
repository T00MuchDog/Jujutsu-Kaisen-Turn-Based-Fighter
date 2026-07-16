package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the defensive-move expiry battle-log line.
 *
 * <p>When a character's block runs out (its AP window ends), the combat engine
 * must emit a {@link CombatEvent.Type#STATUS_EXPIRED} event with a "drops their
 * guard"-style message, so the battle log reports the guard coming down — not
 * just going up on activation.
 *
 * <p>The expiry is tick-based (it lives on the timeline, not the round-based
 * status-effect ticker), so the line must fire at the tick where the block's
 * window ends during resolution, not at round-end.
 */
public class DefenseExpiryLogTest {

    /**
     * Headline case: a PERCENTAGE_BLOCK with a short window (blockDuration 5)
     * placed at tick 1 fires at tick 1, is active through tick 5, and logs its
     * expiry once the sweep reaches tick 6.
     */
    @Test
    void blockExpiryIsLoggedWhenWindowEnds() {
        Move block = new Move.Builder("EXPIRY_BLOCK")
            .name("Short Guard")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(50)
            .blockDuration(5)            // window = [fireTick, fireTick + 4]
            .apCost(10)
            .unleashPoint(1)             // fires at its startTick
            .build();

        CharacterStats stats = new CharacterStats.Builder().speed(100).build();
        Character userChar    = new SorcererCharacter("U", "User", stats, null, List.of(block));
        Character enemyChar   = new SorcererCharacter("E", "Enemy", stats, null, List.of());
        BattleCombatant user   = new BattleCombatant(userChar);
        BattleCombatant enemy  = new BattleCombatant(enemyChar);

        // Place the block at tick 1 → fires at tick 1, window covers ticks [1, 5].
        Timeline userTimeline = new Timeline();
        assertNotNull(userTimeline.placeAt(block, 1, 0));
        user.setTimeline(userTimeline);
        enemy.setTimeline(new Timeline());

        BattleState state = new BattleState(user, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        boolean blockActivated = events.stream()
            .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("raises their block"));
        assertTrue(blockActivated, "The block activation should still be logged.");

        // The new behaviour: a STATUS_EXPIRED event sourced on the user, with the
        // "drops their guard" wording, naming the block move.
        boolean blockExpired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.STATUS_EXPIRED
                        && e.getMessage() != null
                        && e.getMessage().contains("drops their guard")
                        && "Short Guard".equals(e.getMove() != null ? e.getMove().getName() : null));
        assertTrue(blockExpired,
            "The defensive block should log a 'drops their guard' STATUS_EXPIRED event "
            + "when its AP window ends.");

        // Exactly one expiry line — a block must not double-log its own end.
        long expiryCount = events.stream()
            .filter(e -> e.getType() == CombatEvent.Type.STATUS_EXPIRED
                      && e.getMessage() != null
                      && e.getMessage().contains("drops their guard"))
            .count();
        assertEquals(1, expiryCount, "A block should log its expiry exactly once.");
    }

    /**
     * The expiry line must read distinctly from a stat-boost expiry: same event
     * type, different message body. Locks in the per-category wording rule.
     */
    @Test
    void blockExpiryWordingDiffersFromStatBoostExpiry() {
        Move block = new Move.Builder("WORDING_BLOCK")
            .name("Wording Guard")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(50)
            .blockDuration(5)
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats stats = new CharacterStats.Builder().speed(100).build();
        Character userChar = new SorcererCharacter("U", "User", stats, null, List.of(block));
        BattleCombatant user = new BattleCombatant(userChar);
        BattleCombatant enemy = new BattleCombatant(
            new SorcererCharacter("E", "Enemy", stats, null, List.of()));

        Timeline tl = new Timeline();
        tl.placeAt(block, 1, 0);
        user.setTimeline(tl);
        enemy.setTimeline(new Timeline());

        BattleState state = new BattleState(user, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        String guardLine = events.stream()
            .map(CombatEvent::getMessage)
            .filter(m -> m != null && m.contains("drops their guard"))
            .findFirst().orElse(null);
        assertNotNull(guardLine, "Guard expiry wording should be present.");

        // No stat-boost fade wording ("surge fades", "focus wavers", etc.) should
        // appear for a pure defensive move.
        assertFalse(guardLine.toLowerCase().contains("fades")
                 && guardLine.toLowerCase().contains("surge"),
            "Guard expiry wording must not read like a stat-boost fade.");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit/miss rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
