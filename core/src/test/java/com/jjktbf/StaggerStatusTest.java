package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaggerStatusTest {

    @Test
    void staggerIsTickOnlyAndDoesNotModifyStats() {
        BattleCombatant combatant = combatant("C", "Combatant", 80, List.of());
        int strength = combatant.getEffectiveStats().getStrength();

        combatant.addStatusEffect(new StatusEffect(StatusEffectType.STAGGER, 0, 2, 50.0));

        assertTrue(combatant.hasEffect(StatusEffectType.STAGGER));
        assertEquals(strength, combatant.getEffectiveStats().getStrength());
        assertEquals(0.0, combatant.getStatusMagnitude(StatusEffectType.STAGGER));
        assertThrows(IllegalArgumentException.class, () -> new StatusEffect(
            StatusEffectType.STAGGER, 1, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new StatusEffect(
            StatusEffectType.STAGGER, -1, 0, 0.0));
    }

    @Test
    void moveDataPreservesStaggerAsATickOnlyOnHitEffect() {
        MoveData data = new MoveData();
        data.id = "STAGGER_DATA";
        data.name = "Stagger Data";
        data.tags = List.of("ATTACK", "PHYSICAL");
        data.basePower = 10;
        data.apCost = 1;
        data.unleashPoint = 1;
        MoveData.StatusEffectData stagger = new MoveData.StatusEffectData();
        stagger.type = StatusEffectType.STAGGER.name();
        stagger.durationRounds = 0;
        stagger.durationTicks = 3;
        data.onHitEffects = List.of(stagger);

        StatusEffect effect = data.toMove().getOnHitEffects().get(0);

        assertEquals(StatusEffectType.STAGGER, effect.getType());
        assertEquals(0, effect.getDurationRounds());
        assertEquals(3, effect.getDurationTicks());
        assertEquals(0.0, effect.getMagnitude());
    }

    @Test
    void staggerStunsEachTickOfItsDurationAndDoesNotRespectHeavy() {
        Move staggerStrike = new Move.Builder("STAGGER_STRIKE")
            .name("Stagger Strike")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .onHitEffects(List.of(new StatusEffect(StatusEffectType.STAGGER, 0, 2, 0.0)))
            .build();
        Move firstMove = attack("FIRST_MOVE", "First Move", false);
        Move heavySecondMove = attack("SECOND_MOVE", "Heavy Second Move", true);
        Move thirdMove = attack("THIRD_MOVE", "Third Move", false);

        BattleCombatant attacker = combatant("A", "Attacker", 120, List.of(staggerStrike));
        BattleCombatant defender = combatant(
            "D", "Defender", 80, List.of(firstMove, heavySecondMove, thirdMove));
        Timeline attackerTimeline = new Timeline(3);
        attackerTimeline.placeAt(staggerStrike, 1, 0);
        Timeline defenderTimeline = new Timeline(3);
        ActionSegment first = defenderTimeline.placeAt(firstMove, 1, 0);
        ActionSegment second = defenderTimeline.placeAt(heavySecondMove, 2, 0);
        ActionSegment third = defenderTimeline.placeAt(thirdMove, 3, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new Random(1)).resolveRound(state);

        assertTrue(first.isStunned(), "Stagger should apply on the tick it lands.");
        assertTrue(second.isStunned(), "Stagger should continue through its second AP tick.");
        assertFalse(third.isStunned(), "Stagger should end before the third AP tick.");
        assertFalse(defender.hasEffect(StatusEffectType.STAGGER));
        assertTrue(events.stream().anyMatch(event -> event.getType() == CombatEvent.Type.MOVE_FIRED
            && "Third Move".equals(event.getMove().getName())));
        assertEquals(2, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_STUNNED)
            .filter(event -> event.getMessage().contains("was staggered"))
            .count());
        assertTrue(events.stream().anyMatch(event -> event.getType() == CombatEvent.Type.STATUS_EXPIRED
            && event.getTick() == 2
            && event.getMessage().contains("stagger effect expires")));
    }

    @Test
    void oneTickStaggerImmediatelyStunsAChargingSegment() {
        Move staggerStrike = new Move.Builder("ONE_TICK_STAGGER")
            .name("One Tick Stagger")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .onHitEffects(List.of(new StatusEffect(StatusEffectType.STAGGER, 0, 1, 0.0)))
            .build();
        Move chargedMove = new Move.Builder("CHARGED_MOVE")
            .name("Charged Move")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(3)
            .unleashPoint(3)
            .build();

        BattleCombatant attacker = combatant("A", "Attacker", 120, List.of(staggerStrike));
        BattleCombatant defender = combatant("D", "Defender", 80, List.of(chargedMove));
        Timeline attackerTimeline = new Timeline(3);
        attackerTimeline.placeAt(staggerStrike, 1, 0);
        Timeline defenderTimeline = new Timeline(3);
        ActionSegment charged = defenderTimeline.placeAt(chargedMove, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        new CombatResolver(new Random(1)).resolveRound(state);

        assertTrue(charged.isStunned());
        assertFalse(defender.hasEffect(StatusEffectType.STAGGER));
    }

    private static Move attack(String id, String name, boolean heavy) {
        return new Move.Builder(id)
            .name(name)
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .heavy(heavy)
            .apCost(1)
            .unleashPoint(1)
            .build();
    }

    private static BattleCombatant combatant(String id, String name, int speed, List<Move> moves) {
        Character character = new SorcererCharacter(
            id,
            name,
            new CharacterStats.Builder().speed(speed).build(),
            null,
            moves);
        return new BattleCombatant(character);
    }
}
