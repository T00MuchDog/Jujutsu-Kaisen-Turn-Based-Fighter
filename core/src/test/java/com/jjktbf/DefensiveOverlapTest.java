package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
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
 * Regression test for a bug where a defensive move placed under (overlapping)
 * an offensive move on the <em>same</em> combatant's plan was silently dropped
 * when the two boards were merged into the legacy single-timeline, so the
 * defensive move never fired in the battle log.
 *
 * <p>Root cause: {@link BattlePlan#toLegacyTimeline()} re-placed every segment
 * via {@link Timeline#placeAt}, which enforces no-overlap on the single merged
 * board. The offensive and defensive boards are independent grids, so a
 * cross-board overlap is valid and intended — but the merge treated it as a
 * collision and discarded the second (defensive) segment.
 *
 * <p>The real symptom: a defensive move fires correctly when placed before or
 * after the offensive move (no overlap), but is dropped from the battle log
 * whenever the two segments share any ticks.
 */
public class DefensiveOverlapTest {

    /**
     * Headline case: one combatant places an offensive move AND a defensive
     * block on their plan at identical ticks (legal — they live on opposite
     * boards). Both must survive the merge and fire during resolution; the
     * defensive block must appear in the battle log.
     */
    @Test
    void defensiveMoveOverlappingOffensiveMoveOnSamePlanFiresInBattleLog() {
        Move attack = new Move.Builder("OVERLAP_ATTACK")
            .name("Overlap Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        Move block = new Move.Builder("OVERLAP_BLOCK")
            .name("Overlap Guard")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(100)   // full block, for a loud log line
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats stats = new CharacterStats.Builder().speed(100).build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", stats, null, List.of(attack, block));
        Character defenderChar = new SorcererCharacter("D", "Defender", stats, null, List.of());
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender  = new BattleCombatant(defenderChar);

        // The attacker plans BOTH moves at ticks [1,10] (both fire at tick 1).
        // They land on opposite boards, so this overlap is a legal plan.
        BattlePlan attackerPlan = new BattlePlan(150, 1000);
        assertNotNull(attackerPlan.place(attack, 1, 0), "Offensive placement should succeed.");
        assertNotNull(attackerPlan.place(block, 1, 0),
            "Defensive placement at the same ticks as the offensive move must succeed "
            + "(it is on the defensive board).");

        // Both boards carry exactly one segment.
        assertEquals(1, attackerPlan.offensiveTimeline().getSegments().size());
        assertEquals(1, attackerPlan.defensiveTimeline().getSegments().size());

        Timeline merged = attackerPlan.toLegacyTimeline();

        // The merge must keep BOTH segments: offensive AND defensive.
        assertEquals(2, merged.getSegments().size(),
            "Merged timeline must retain BOTH the offensive and the overlapping defensive "
            + "segment (the bug dropped the defensive one during the cross-board merge).");

        attacker.setTimeline(merged);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        boolean attackFired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                        && "Overlap Strike".equals(e.getMove().getName()));
        boolean blockFired = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                        && "Overlap Guard".equals(e.getMove().getName()));

        assertTrue(attackFired, "Offensive move should fire.");
        assertTrue(blockFired,
            "Defensive move overlapping an offensive move (on the same plan, cross-board) "
            + "should fire and appear in the battle log — it was silently dropped before the fix.");

        // The block's activation message is the visible "defensive move played"
        // line in the log.
        boolean blockRaised = events.stream()
            .anyMatch(e -> e.getType() == CombatEvent.Type.STATUS_APPLIED
                        && e.getMessage() != null
                        && e.getMessage().contains("raises their block"));
        assertTrue(blockRaised, "The defensive block activation should be logged.");
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit/miss rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
