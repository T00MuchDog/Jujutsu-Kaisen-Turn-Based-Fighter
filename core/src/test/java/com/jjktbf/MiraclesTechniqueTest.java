package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiraclesTechniqueTest {

    @Test
    void miraclesStartFullAndNegateLethalDamageWithoutUsingAStatus() {
        BattleCombatant owner = miracleCombatant("OWNER", List.of());
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        List<CombatEvent> startEvents = new CombatResolver(new FixedRandom()).processRoundStart(state);

        int hpBeforeLethalHit = owner.getCurrentHp();
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
        assertEquals("OWNER gains 6 Miracles (6/6 remaining).", startEvents.get(0).getMessage());
        assertTrue(owner.getActiveEffects().isEmpty());
        assertEquals(0, owner.receiveDamage(hpBeforeLethalHit));
        assertEquals(hpBeforeLethalHit, owner.getCurrentHp());
        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(owner));
        List<CombatEvent> aversionEvents = owner.getCodedAbilities().drainPendingEvents(1);
        assertEquals(1, aversionEvents.size());
        assertEquals("OWNER uses 1 Miracle to avert a fatal blow (5/6 remaining).",
            aversionEvents.get(0).getMessage());
        assertEquals(5, aversionEvents.get(0).getCodedAbilityState().currentValue());
        assertTrue(owner.getCodedAbilities().drainPendingEvents(1).isEmpty());

        owner.clearStatusEffects();
        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(owner));
    }

    @Test
    void missesAndBlackFlashesRestoreMiraclesWithoutExceedingTheCap() {
        BattleCombatant owner = miracleCombatant("OWNER", List.of());
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        new CombatResolver(new FixedRandom()).processRoundStart(state);
        AbilityActivationEngine engine = new AbilityActivationEngine(new SeededRandomSource(new FixedRandom()));
        Move attack = attack("ATTACK");

        owner.receiveDamage(owner.getCurrentHp());
        List<CombatEvent> missedEvents = engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_MISSED, enemy, owner, attack, 1));
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
        assertEquals("OWNER gains 1 Miracle (6/6 remaining).", missedEvents.get(0).getMessage());

        owner.receiveDamage(owner.getCurrentHp());
        owner.receiveDamage(owner.getCurrentHp());
        List<CombatEvent> blackFlashEvents = engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.BLACK_FLASH, owner, enemy, attack, 2));
        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(owner));
        assertEquals("OWNER gains 1 Miracle (5/6 remaining).", blackFlashEvents.get(0).getMessage());

        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_MISSED, enemy, owner, attack, 3));
        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.BLACK_FLASH, owner, enemy, attack, 4));
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
    }

    @Test
    void eachCombatantGetsAnIndependentMiraclesRuntime() {
        Character sharedCharacter = new SorcererCharacter(
            "MIRACLE_USER", "Miracle User", new CharacterStats.Builder().build(),
            "Miracles", List.of(), miracleAbilities());
        BattleCombatant first = new BattleCombatant(sharedCharacter);
        BattleCombatant second = new BattleCombatant(sharedCharacter);

        new CombatResolver(new FixedRandom()).processRoundStart(new BattleState(
            first, combatant("ENEMY_ONE", List.of(), List.of())));
        new CombatResolver(new FixedRandom()).processRoundStart(new BattleState(
            second, combatant("ENEMY_TWO", List.of(), List.of())));
        first.receiveDamage(first.getCurrentHp());

        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(first));
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(second));
    }

    private static BattleCombatant miracleCombatant(String id, List<Move> moves) {
        return combatant(id, moves, miracleAbilities());
    }

    private static BattleCombatant combatant(String id, List<Move> moves, List<Ability> abilities) {
        Character character = new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), "Miracles", moves, abilities);
        return new BattleCombatant(character);
    }

    private static List<Ability> miracleAbilities() {
        return List.of(
            codedAbility("Miracle Reservoir", "MIRACLE_RESERVOIR", MiraclesAbility.RESERVOIR),
            codedAbility("Fateful Reprieve", "FATEFUL_REPRIEVE", MiraclesAbility.FATEFUL_REPRIEVE),
            codedAbility("Fortune Reclaimed", "FORTUNE_RECLAIMED", MiraclesAbility.FORTUNE_RECLAIMED)
        );
    }

    private static Ability codedAbility(String name, String id, String feature) {
        AbilityData ability = new AbilityData();
        ability.id = id;
        ability.name = name;
        ability.category = "PASSIVE";
        ability.sourceType = "TECHNIQUE";
        ability.sourceValue = "Miracles";
        ability.codedAbilityKey = MiraclesAbility.KEY;
        ability.codedFeature = feature;
        ability.effects = List.of();
        return new Ability(ability);
    }

    private static int miracleCount(BattleCombatant combatant) {
        return combatant.getCodedAbilities().states().stream()
            .filter(state -> MiraclesAbility.KEY.equals(state.key()))
            .findFirst()
            .orElseThrow()
            .currentValue();
    }

    private static Move attack(String id) {
        return new Move.Builder(id)
            .name(id)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
    }

    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return true; }
    }
}
