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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefenseLogTest {

    @Test
    void defensiveMoveLogsUseWithoutStanceChatter() {
        Move block = new Move.Builder("GUARD")
            .name("Short Guard")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(50)
            .blockDuration(5)
            .apCost(10)
            .unleashPoint(1)
            .build();
        CharacterStats stats = new CharacterStats.Builder().speed(100).build();
        Character userCharacter = new SorcererCharacter(
            "U", "User", stats, null, List.of(block));
        Character enemyCharacter = new SorcererCharacter(
            "E", "Enemy", stats, null, List.of());
        BattleCombatant user = new BattleCombatant(userCharacter);
        BattleCombatant enemy = new BattleCombatant(enemyCharacter);
        Timeline timeline = new Timeline();
        timeline.placeAt(block, 1, 0);
        user.setTimeline(timeline);
        enemy.setTimeline(new Timeline());
        BattleState state = new BattleState(user, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new Random(1)).resolveRound(state);

        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_FIRED
                && "User used Short Guard!".equals(event.getMessage())));
        assertFalse(events.stream().anyMatch(event ->
            event.getMove() == block
                && (event.getType() == CombatEvent.Type.STATUS_APPLIED
                    || event.getType() == CombatEvent.Type.STATUS_EXPIRED)));
    }

    @Test
    void successfulHitLogsOutcomeWithoutDamageAmount() {
        Move attack = new Move.Builder("STRIKE")
            .name("Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
        CharacterStats stats = new CharacterStats.Builder().speed(100).build();
        BattleCombatant attacker = new BattleCombatant(new SorcererCharacter(
            "A", "Attacker", stats, null, List.of(attack)));
        BattleCombatant defender = new BattleCombatant(new SorcererCharacter(
            "D", "Defender", stats, null, List.of()));
        Timeline timeline = new Timeline();
        var segment = timeline.placeAt(attack, 1, 0);
        assertNotNull(segment);
        segment.setTarget(defender.getInstanceId());
        attacker.setTimeline(timeline);
        defender.setTimeline(new Timeline());
        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        CombatEvent damage = new CombatResolver(new Random(1)).resolveRound(state).stream()
            .filter(event -> event.getType() == CombatEvent.Type.DAMAGE_DEALT)
            .findFirst().orElse(null);

        assertNotNull(damage);
        assertTrue(damage.getIntValue() > 0, "The structural event must retain its damage value.");
        assertTrue("Strike hit Defender!".equals(damage.getMessage()));
    }
}
