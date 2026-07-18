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
 * Regression test for the stun-vs-already-fired-defensive-move interaction.
 *
 * <p>Design rule (see game_design_decisions): a stun stops a move from occurring;
 * it must NOT deactivate a move that already fired. A defensive block that fires
 * before a STUN-tagged hit lands on the same tick must keep protecting for the
 * rest of its AP window — its segment is not stunned, its activation is logged,
 * its eventual "drops their guard" expiry still fires when the window ends, and
 * no spurious mid-window expiry or "was stunned" line appears.
 */
public class StunDoesNotDeactivateFiredBlockTest {

    /**
     * Headline case. The defender is faster so its instant block resolves first
     * at tick 1, then the attacker's instant STUN move hits on the same tick.
     * Without the fix, {@code resolveStunTag} would stun the block segment (it
     * is still inside its AP window on tick 1), killing its protection for the
     * rest of the round and emitting a spurious guard-expiry line. With the fix,
     * the fired block is immune.
     */
    @Test
    void stunDoesNotDeactivateBlockThatAlreadyFired() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .stun(true)
            // Wide AP window so the resolution sweep runs well past the block's
            // window (tick 10). This both matches a realistic "fight continues"
            // scenario and lets the block's natural guard-expiry be detected,
            // which only fires once the sweep advances past the window's end.
            .apCost(15)
            .unleashPoint(1)
            .build();

        Move block = new Move.Builder("GUARD")
            .name("Guard")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(50)
            .blockDuration(10)            // window = [1, 10] after firing at tick 1
            .apCost(10)
            .unleashPoint(1)
            .build();

        // Defender is faster so its instant block fires first this tick.
        CharacterStats attackerStats = new CharacterStats.Builder().speed(80).build();
        CharacterStats defenderStats = new CharacterStats.Builder().speed(120).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", attackerStats, null, List.of(stunAttack));
        Character defenderChar = new SorcererCharacter("D", "Defender", defenderStats, null, List.of(block));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(30);
        attackerTimeline.placeAt(stunAttack, 1, 0);
        Timeline defenderTimeline = new Timeline(30);
        com.jjktbf.model.combat.ActionSegment blockSeg =
            defenderTimeline.placeAt(block, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        // The block fired and therefore cannot be retro-stunned.
        assertTrue(blockSeg.hasFired(),
            "The block should have fired.");
        assertFalse(blockSeg.isStunned(),
            "A defensive block that already fired must not be stunned by a later STUN hit.");

        // No "was stunned and could not move" should be emitted for the defender —
        // the only segment on its timeline is the already-fired block.
        boolean defenderStunnedEvent = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_STUNNED
                        && e.getTarget() != null
                        && "Defender".equals(e.getTarget().getCharacter().getName()));
        assertFalse(defenderStunnedEvent,
            "No MOVE_STUNNED event should be emitted when the only target already fired.");

        // The block's activation line must still have fired.
        boolean blockActivated = events.stream()
            .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("raises their block"));
        assertTrue(blockActivated, "The block activation should still be logged.");

        // Its natural expiry must still be logged exactly once (no mid-window
        // spurious expiry from the stun, no suppression either).
        long expiryCount = events.stream()
            .filter(e -> e.getType() == CombatEvent.Type.STATUS_EXPIRED
                      && e.getMessage() != null
                      && e.getMessage().contains("drops their guard"))
            .count();
        assertEquals(1, expiryCount,
            "The block should log exactly one natural guard-expiry line at its window end.");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
