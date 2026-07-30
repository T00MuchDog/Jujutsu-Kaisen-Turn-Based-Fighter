package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
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
 * Regression test for the speed-priority defense rule.
 *
 * <p>Rule (see CombatResolver): a defense only contests an attack if it has
 * already fired this tick — i.e. it won the same-tick speed ordering (instant
 * first, then higher Speed, then random). The old rule let a not-yet-fired
 * same-tick defense contest regardless of speed; that is no longer the case.
 *
 * <p>This locks BOTH directions of the rule with an instant attack and an
 * instant full block firing on the same tick:
 * <ul>
 *   <li>Faster attacker vs slower defender → the block has not fired yet when
 *       the attack resolves, so the attack CONNECTS (not blocked).</li>
 *   <li>Faster defender vs slower attacker → the block fires first, so the
 *       attack is BLOCKED.</li>
 * </ul>
 * The lesson: defending means committing the block to fire BEFORE the attack
 * lands, not merely on the same tick.
 */
public class SpeedPriorityDefenseTest {

    /** Faster attacker: a same-tick instant block must NOT contest the attack. */
    @Test
    void fasterAttackerBypassesSlowerSameTickBlock() {
        Resolution result = resolveWithSpeeds(120, 80);

        assertFalse(anyBlocked(result.events()),
            "A slower same-tick block must not contest a faster attack "
            + "(it has not fired yet when the attack resolves).");
        assertTrue(anyDamageDealt(result.events()),
            "The faster attack should connect and deal damage.");
    }

    /** Faster defender: a same-tick instant block that fires first DOES contest. */
    @Test
    void fasterDefenderBlocksSlowerSameTickAttack() {
        Resolution result = resolveWithSpeeds(80, 120);

        assertTrue(anyBlocked(result.events()),
            "A faster same-tick block that fires first must contest the attack.");
    }

    // -------------------------------------------------------------------------

    private static final Move ATTACK = new Move.Builder("FAST_ATTACK")
        .name("Fast Strike")
        .category(MoveCategory.PHYSICAL)
        .basePower(10)
        .neverMiss(true)
        .apCost(2)
        .unleashPoint(1)
        .build();

    private static final Move FULL_BLOCK = new Move.Builder("INSTANT_BLOCK")
        .name("Instant Block")
        .category(MoveCategory.DEFENSIVE)
        .defenseType(DefenseType.BLOCK)
        .blockStyle(BlockStyle.PERCENTAGE)
        .blockDamageReduction(100)   // full block, for an unambiguous result
        .apCost(5)
        .unleashPoint(1)
        .build();   // potency defaults to 1 for both attack and block → block applies

    /**
     * Build both combatants, place the attack and block to fire on tick 1
     * (instant), and resolve a full round. Speeds are set per case.
     */
    private static Resolution resolveWithSpeeds(int attackerSpeed, int defenderSpeed) {
        Character attackerChar = new SorcererCharacter(
            "A", "Attacker",
            new CharacterStats.Builder().vitality(300).speed(attackerSpeed).build(),
            null, List.of(ATTACK));
        Character defenderChar = new SorcererCharacter(
            "D", "Defender",
            new CharacterStats.Builder().vitality(300).speed(defenderSpeed).build(),
            null, List.of(FULL_BLOCK));

        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);

        Timeline attackerTimeline = new Timeline(10);
        assertNotNull(attackerTimeline.placeAt(ATTACK, 1, 0));
        Timeline defenderTimeline = new Timeline(10);
        assertNotNull(defenderTimeline.placeAt(FULL_BLOCK, 1, 0));
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom(0.0)).resolveRound(state);
        return new Resolution(attacker, defender, events);
    }

    private static boolean anyBlocked(List<CombatEvent> events) {
        return events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_BLOCKED);
    }

    private static boolean anyDamageDealt(List<CombatEvent> events) {
        return events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.DAMAGE_DEALT);
    }

    private record Resolution(BattleCombatant attacker, BattleCombatant defender,
                              List<CombatEvent> events) {}

    /** Deterministic RNG: always returns the same double, for reproducible hit rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }
}
