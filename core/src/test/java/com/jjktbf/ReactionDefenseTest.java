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
import com.jjktbf.model.move.DefenseTiming;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REACTION-timing defences: they arm at their fire tick without opening a
 * window, trigger the moment a hostile component is about to resolve against
 * the wielder, open their window at that tick (contesting the triggering
 * attack), trigger once per placement, and never count as perfect reads.
 */
public class ReactionDefenseTest {

    private static final Move ATTACK = new Move.Builder("RE_ATTACK")
        .name("Strike")
        .category(MoveCategory.PHYSICAL)
        .basePower(30)
        .neverMiss(true)
        .apCost(2)
        .unleashPoint(1)
        .build();

    /** A two-component attack: impact at fire tick and fire tick + 2. */
    private static final Move DELAYED_ATTACK = new Move.Builder("RE_DELAYED_ATTACK")
        .name("Flurry")
        .category(MoveCategory.PHYSICAL)
        .hitComponents(List.of(
            new HitComponent(15, EnumSet.of(MoveTag.PHYSICAL), 0, false, true, 1.0, List.of()),
            new HitComponent(15, EnumSet.of(MoveTag.PHYSICAL), 2, false, true, 1.0, List.of())))
        .neverMiss(true)
        .apCost(4)
        .unleashPoint(1)
        .build();

    /** Arms at tick 1; when triggered, opens a 4-tick 50% block window. */
    private static final Move REACTION_BLOCK = new Move.Builder("REACTION_BLOCK")
        .name("Readied Guard")
        .category(MoveCategory.DEFENSIVE)
        .defenseType(DefenseType.BLOCK)
        .blockStyle(BlockStyle.PERCENTAGE)
        .blockDamageReduction(50)
        .blockDuration(4)
        .defenseTiming(DefenseTiming.REACTION)
        .apCost(5)
        .unleashPoint(1)
        .build();

    /** Arms at tick 1; when triggered, opens a 4-tick 10% dodge window. */
    private static final Move REACTION_DODGE = new Move.Builder("REACTION_DODGE")
        .name("Shadow Watch")
        .category(MoveCategory.DEFENSIVE)
        .defenseType(DefenseType.DODGE)
        .dodgeChance(10)
        .blockDuration(4)
        .defenseTiming(DefenseTiming.REACTION)
        .apCost(5)
        .unleashPoint(1)
        .build();

    /** An armed reaction has no window; triggering opens it anchored at the trigger tick. */
    @Test
    void armedReactionHasNoWindowUntilTriggered() {
        Timeline timeline = new Timeline(20);
        ActionSegment armed = timeline.placeAt(REACTION_BLOCK, 1, 0);
        assertNotNull(armed);
        armed.markFired();

        assertNull(timeline.activeDefenseAt(3, ATTACK, null, DefenseType.BLOCK, true),
            "An armed-but-untriggered reaction must not contest anything.");

        ActionSegment triggered = timeline.triggerArmedReaction(5, ATTACK);
        assertNotNull(triggered, "A matching attack triggers the armed reaction.");
        assertTrue(triggered.isReactionTriggered());
        assertEquals(5, triggered.getFireTick(),
            "The triggered window opens at the trigger tick.");
        assertNotNull(timeline.activeDefenseAt(5, ATTACK, null, DefenseType.BLOCK, true),
            "After triggering, the window contests the triggering tick.");
        assertNotNull(timeline.activeDefenseAt(8, ATTACK, null, DefenseType.BLOCK, true),
            "The authored 4-tick window covers trigger tick + 3.");
        assertNull(timeline.activeDefenseAt(9, ATTACK, null, DefenseType.BLOCK, true),
            "The window ends after its authored duration.");

        assertNull(timeline.triggerArmedReaction(9, ATTACK),
            "One trigger per placement — the reaction cannot re-arm.");
    }

    /** A later attack triggers the armed block and is contested by it. */
    @Test
    void laterAttackTriggersArmedBlockAndIsContested() {
        List<CombatEvent> events = resolveRound(REACTION_BLOCK, ATTACK, 5);

        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                && e.getMessage() != null && e.getMessage().contains("reacted to")),
            "The reaction's trigger is announced.");
        assertEquals(1, count(events, CombatEvent.Type.MOVE_BLOCK_REDUCED),
            "The triggering attack is contested by the opened window.");
    }

    /** The reaction triggers once: a later attack outside the opened window connects cleanly. */
    @Test
    void reactionTriggersOncePerPlacement() {
        List<CombatEvent> events = resolveRound(REACTION_BLOCK, ATTACK, 5, 15);

        assertEquals(1, events.stream().filter(e -> e.getType() == CombatEvent.Type.MOVE_FIRED
                && e.getMessage() != null && e.getMessage().contains("reacted to")).count(),
            "Only the first attack triggers the reaction.");
        assertEquals(1, count(events, CombatEvent.Type.MOVE_BLOCK_REDUCED),
            "Only the triggering attack (tick 5) is contested; window [5,8].");
        assertEquals(2, count(events, CombatEvent.Type.DAMAGE_DEALT),
            "Both attacks deal damage (one reduced, one clean).");
    }

    /** A triggered reaction's fire tick equals the impact tick, yet it must NOT perfect-read. */
    @Test
    void reactionNeverPerfectReads() {
        List<CombatEvent> events = resolveRound(REACTION_DODGE, ATTACK, 5);

        assertEquals(0, count(events, CombatEvent.Type.MOVE_DODGED),
            "A reaction is excluded from perfect reads — its 10% roll fails (rng 0.5).");
        assertEquals(1, count(events, CombatEvent.Type.DAMAGE_DEALT));
        assertFalse(events.stream().anyMatch(e -> e.getMessage() != null
                && e.getMessage().contains("PERFECT READ")));
    }

    /** Delayed components resolve inside the triggered window and are contested too. */
    @Test
    void delayedComponentsWithinTriggeredWindowAreContested() {
        List<CombatEvent> events = resolveRound(REACTION_BLOCK, DELAYED_ATTACK, 5);

        assertEquals(2, count(events, CombatEvent.Type.MOVE_BLOCK_REDUCED),
            "Impacts at ticks 5 and 7 both fall inside the triggered window [5,8].");
    }

    // -------------------------------------------------------------------------

    private static List<CombatEvent> resolveRound(Move defense, Move attack, int attackStart) {
        return resolveRound(defense, attack, attackStart, attackStart);
    }

    private static List<CombatEvent> resolveRound(
        Move defense, Move attack, int firstAttackStart, int secondAttackStart
    ) {
        BattleCombatant attacker = new BattleCombatant(new SorcererCharacter(
            "A", "Attacker",
            new CharacterStats.Builder().vitality(300).speed(80).build(),
            null, List.of(attack)));
        BattleCombatant defender = new BattleCombatant(new SorcererCharacter(
            "D", "Defender",
            new CharacterStats.Builder().vitality(300).speed(120).build(),
            null, List.of(defense)));

        Timeline attackerTimeline = new Timeline(20);
        assertNotNull(attackerTimeline.placeAt(attack, firstAttackStart, 0));
        if (secondAttackStart != firstAttackStart) {
            assertNotNull(attackerTimeline.placeAt(attack, secondAttackStart, 0));
        }
        Timeline defenderTimeline = new Timeline(20);
        assertNotNull(defenderTimeline.placeAt(defense, 1, 0)); // arms at tick 1
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new FixedRandom()).resolveRound(state);
    }

    private static long count(List<CombatEvent> events, CombatEvent.Type type) {
        return events.stream().filter(e -> e.getType() == type).count();
    }

    /** Deterministic mid-range RNG: dodge and damage rolls land on ordinary outcomes. */
    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.5; }
    }
}
