package com.jjktbf;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defense-uses: how many times a defence may activate while its window is
 * active. 0 = unlimited (the historical behaviour); a positive cap only
 * restricts within the window — it never changes the window's duration.
 */
public class DefenseUsesTest {

    /** Two instant attacks: one at tick 2 and one at tick 4. */
    private static final Move ATTACK = new Move.Builder("USES_ATTACK")
        .name("Strike")
        .category(MoveCategory.PHYSICAL)
        .basePower(30)
        .neverMiss(true)
        .apCost(2)
        .unleashPoint(1)
        .build();

    /** Instant 50% block whose window covers both attack ticks. */
    private static Move blockWithUses(int defenseUses) {
        return new Move.Builder("USES_BLOCK_" + defenseUses)
            .name("Guard")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(50)
            .blockDuration(8)
            .defenseUses(defenseUses)
            .apCost(5)
            .unleashPoint(1)
            .build();
    }

    /** Default 0 = unlimited: every matching attack inside the window is reduced. */
    @Test
    void unlimitedUsesBlockContestsEveryAttackInWindow() {
        List<CombatEvent> events = resolveRound(blockWithUses(0));

        assertEquals(2, count(events, CombatEvent.Type.MOVE_BLOCK_REDUCED),
            "An uncapped block must contest both attacks inside its window.");
    }

    /** Uses = 1: the first attack is reduced, the second connects cleanly. */
    @Test
    void singleUseBlockContestsOnlyTheFirstAttack() {
        List<CombatEvent> events = resolveRound(blockWithUses(1));

        assertEquals(1, count(events, CombatEvent.Type.MOVE_BLOCK_REDUCED),
            "A 1-use block must stop applying after its first contest.");
        assertEquals(2, count(events, CombatEvent.Type.DAMAGE_DEALT),
            "Both attacks still land damage (one reduced, one clean).");
    }

    /** Uses = 2: both attacks are reduced, matching the uncapped behaviour here. */
    @Test
    void twoUseBlockContestsBothAttacks() {
        List<CombatEvent> events = resolveRound(blockWithUses(2));

        assertEquals(2, count(events, CombatEvent.Type.MOVE_BLOCK_REDUCED));
    }

    /** A dodge spends its one activation on a successful dodge; the next attack is unopposed. */
    @Test
    void singleUseDodgesOnlyTheFirstAttack() {
        Move dodge = new Move.Builder("USES_DODGE")
            .name("Slip")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .blockDuration(8)
            .defenseUses(1)
            .apCost(5)
            .unleashPoint(1)
            .build();
        List<CombatEvent> events = resolveRound(dodge);

        assertEquals(1, count(events, CombatEvent.Type.MOVE_DODGED),
            "The first attack is dodged.");
        assertEquals(1, count(events, CombatEvent.Type.DAMAGE_DEALT),
            "The second attack connects — the dodge's single use is spent.");
    }

    /** Segment-level behaviour: the uses counter and the timeline's skip of exhausted defences. */
    @Test
    void exhaustedDefenseStopsMatchingInTimelineQueries() {
        Move capped = blockWithUses(1);
        Timeline timeline = new Timeline(10);
        ActionSegment segment = timeline.placeAt(capped, 1, 0);
        assertNotNull(segment);
        segment.markFired();

        assertNotNull(timeline.activeDefenseAt(3, ATTACK, null, DefenseType.BLOCK, true),
            "Before consumption the capped block still contests.");
        segment.consumeDefenseUse();
        assertTrue(segment.isDefenseUsesExhausted());
        assertNull(timeline.activeDefenseAt(3, ATTACK, null, DefenseType.BLOCK, true),
            "An exhausted block must no longer match, even inside its window.");
    }

    /** An uncapped defence never exhausts, no matter how many uses are consumed. */
    @Test
    void uncappedDefenseNeverExhausts() {
        ActionSegment segment = new ActionSegment(blockWithUses(0), 1, 0);
        for (int i = 0; i < 5; i++) segment.consumeDefenseUse();
        assertFalse(segment.isDefenseUsesExhausted());
    }

    // -------------------------------------------------------------------------

    private static List<CombatEvent> resolveRound(Move defense) {
        BattleCombatant attacker = new BattleCombatant(new SorcererCharacter(
            "A", "Attacker",
            new CharacterStats.Builder().vitality(300).speed(80).build(),
            null, List.of(ATTACK)));
        BattleCombatant defender = new BattleCombatant(new SorcererCharacter(
            "D", "Defender",
            new CharacterStats.Builder().vitality(300).speed(120).build(),
            null, List.of(defense)));

        Timeline attackerTimeline = new Timeline(10);
        assertNotNull(attackerTimeline.placeAt(ATTACK, 2, 0));  // fires tick 2
        assertNotNull(attackerTimeline.placeAt(ATTACK, 4, 0));  // fires tick 4
        Timeline defenderTimeline = new Timeline(10);
        assertNotNull(defenderTimeline.placeAt(defense, 1, 0)); // fires tick 1, window covers 2 and 4
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new FixedRandom()).resolveRound(state);
    }

    private static long count(List<CombatEvent> events, CombatEvent.Type type) {
        return events.stream().filter(e -> e.getType() == type).count();
    }

    /** Deterministic RNG: always returns the same double, for reproducible rolls. */
    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.0; }
    }
}
