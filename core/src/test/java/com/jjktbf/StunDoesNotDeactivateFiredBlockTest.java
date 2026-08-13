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
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the stun-vs-already-fired-defensive-move interaction.
 *
 * <p>Design rule (see game_design_decisions): a stun stops a move from occurring;
 * it must NOT deactivate a move that already fired. A defensive block that fires
 * before a stun effect lands on the same tick must keep protecting for the
 * rest of its AP window. Its segment is not stunned and no spurious "was
 * stunned" line appears.
 */
public class StunDoesNotDeactivateFiredBlockTest {

    /**
     * Headline case. The defender is faster so its instant block resolves first
     * at tick 1, then the attacker's instant stun move hits on the same tick.
     * Without the fix, the effect would stun the block segment (it
     * is still inside its AP window on tick 1), killing its protection for the
     * rest of the round. With the fix, the fired block is immune.
     */
    @Test
    void stunDoesNotDeactivateBlockThatAlreadyFired() {
        Move stunAttack = new Move.Builder("STUN_ATTACK")
            .name("Stun Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .effects(List.of(stunEffect()))
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
            .defenseType(DefenseType.BLOCK).blockStyle(BlockStyle.PERCENTAGE)
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

        assertTrue(events.stream().anyMatch(e ->
            e.getType() == CombatEvent.Type.MOVE_FIRED && e.getMove() == block));
        assertFalse(events.stream().anyMatch(e ->
            e.getMove() == block
                && (e.getType() == CombatEvent.Type.STATUS_APPLIED
                    || e.getType() == CombatEvent.Type.STATUS_EXPIRED)));
    }

    /** Deterministic RNG: always returns the same double, for reproducible hit rolls. */
    private static final class FixedRandom extends Random {
        private final double value;
        private FixedRandom(double value) { this.value = value; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return value < 0.5; }
    }

    private static MoveEffectData stunEffect() {
        MoveEffectData effect = AbilityEffectType.STUN_CURRENT_ACTION.createDefaultMoveEffect();
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        return effect;
    }
}
