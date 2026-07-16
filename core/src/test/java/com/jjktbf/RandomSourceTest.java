package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RandomSourceTest {

    @Test
    void sameSeedProducesSameBattleOutput() {
        List<String> first = resolveTieRound(123456789L);
        List<String> second = resolveTieRound(123456789L);

        assertEquals(first, second);
    }

    @Test
    void collidingTieKeysFallBackToStableInsertionOrder() {
        BattleState state = createTieState();

        List<String> firingOrder = new CombatResolver(new ConstantRandomSource())
            .resolveRound(state)
            .stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_FIRED)
            .map(event -> event.getMove().getName())
            .toList();

        assertEquals(List.of("Player Attack", "Player Utility", "Enemy Attack"), firingOrder);
    }

    private static List<String> resolveTieRound(long seed) {
        return new CombatResolver(new SeededRandomSource(seed))
            .resolveRound(createTieState())
            .stream()
            .map(CombatEvent::toString)
            .toList();
    }

    private static BattleState createTieState() {
        Move playerAttack = attack("PLAYER_ATTACK", "Player Attack");
        Move playerUtility = new Move.Builder("PLAYER_UTILITY")
            .name("Player Utility")
            .category(MoveCategory.UTILITY)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
        Move enemyAttack = attack("ENEMY_ATTACK", "Enemy Attack");

        CharacterStats equalStats = new CharacterStats.Builder().speed(80).build();
        Character playerCharacter = new SorcererCharacter(
            "PLAYER", "Player", equalStats, null, List.of(playerAttack, playerUtility));
        Character enemyCharacter = new SorcererCharacter(
            "ENEMY", "Enemy", equalStats, null, List.of(enemyAttack));
        BattleCombatant player = new BattleCombatant(playerCharacter);
        BattleCombatant enemy = new BattleCombatant(enemyCharacter);

        BattlePlan playerPlan = new BattlePlan(2, 0);
        assertNotNull(playerPlan.place(playerAttack, 1, 0));
        assertNotNull(playerPlan.place(playerUtility, 1, 0));
        player.setTimeline(playerPlan.toLegacyTimeline());

        Timeline enemyTimeline = new Timeline(1);
        assertNotNull(enemyTimeline.placeAt(enemyAttack, 1, 0));
        enemy.setTimeline(enemyTimeline);

        BattleState state = new BattleState(player, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return state;
    }

    private static Move attack(String id, String name) {
        return new Move.Builder(id)
            .name(name)
            .category(MoveCategory.CURSED_ENERGY)
            .basePower(1)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
    }

    private static final class ConstantRandomSource implements RandomSource {
        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() { return 0.5; }
        @Override public boolean nextBoolean() { return false; }
    }
}
