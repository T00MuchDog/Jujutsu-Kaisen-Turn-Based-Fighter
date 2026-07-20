package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.PassiveAbilityEngine;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.technique.InnateTechniqueData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        PassiveAbilityEngine engine = new PassiveAbilityEngine(new SeededRandomSource(new FixedRandom()));
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
    void bundledMiracleCreationIsLateHighCostUtilityAndCreatesOneMiracle() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<InnateTechniqueData> techniques = mapper.readValue(
            dataFile("techniques", "all_techniques.json").toFile(), new TypeReference<>() { });
        List<AbilityData> abilityData = mapper.readValue(
            dataFile("abilities", "all_abilities.json").toFile(), new TypeReference<>() { });
        List<MoveData> moves = mapper.readValue(
            dataFile("moves", "all_moves.json").toFile(), new TypeReference<>() { });

        assertTrue(techniques.stream().anyMatch(technique -> "Miracles".equals(technique.name)));
        List<AbilityData> miracleDefinitions = abilityData.stream()
            .filter(ability -> "Miracles".equals(ability.sourceValue))
            .toList();
        assertEquals(3, miracleDefinitions.size());
        assertEquals(Set.of(
            MiraclesAbility.RESERVOIR,
            MiraclesAbility.FATEFUL_REPRIEVE,
            MiraclesAbility.FORTUNE_RECLAIMED
        ), miracleDefinitions.stream().map(ability -> ability.codedFeature).collect(java.util.stream.Collectors.toSet()));
        for (AbilityData ability : miracleDefinitions) {
            assertEquals(MiraclesAbility.KEY, ability.codedAbilityKey);
            assertTrue(CodedAbilityRegistry.supportsAbility(ability.codedAbilityKey, ability.codedFeature));
            assertTrue(ability.effects.isEmpty());
        }

        MoveData creationData = moves.stream()
            .filter(move -> "Miracle Creation".equals(move.name))
            .findFirst()
            .orElseThrow();
        assertEquals("Miracles", creationData.requiredTechniqueId);
        assertTrue(creationData.tags.contains("UTILITY"));
        assertTrue(creationData.tags.contains("INNATE_TECHNIQUE"));
        assertTrue(creationData.tags.contains("CURSED_ENERGY"));
        assertEquals(15, creationData.apCost);
        assertEquals(13, creationData.unleashPoint);
        assertEquals(60, creationData.baseCeCost);
        assertEquals(MiraclesAbility.KEY, creationData.codedAbilityKey);
        assertEquals(MiraclesAbility.CREATE, creationData.codedAction);
        assertTrue(CodedAbilityRegistry.supportsMoveAction(
            creationData.codedAbilityKey, creationData.codedAction));

        Move creation = creationData.toMove();
        List<Ability> abilities = miracleDefinitions.stream().map(Ability::new).toList();
        BattleCombatant owner = combatant("OWNER", List.of(creation), abilities);
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        CombatResolver resolver = new CombatResolver(new FixedRandom());
        resolver.processRoundStart(state);
        owner.receiveDamage(owner.getCurrentHp());

        Timeline ownerTimeline = new Timeline(20);
        assertNotNull(ownerTimeline.placeAt(creation, 1, owner.computeMoveCeCost(creation)));
        owner.setTimeline(ownerTimeline);
        enemy.setTimeline(new Timeline(20));
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = resolver.resolveRound(state);
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
        assertTrue(events.stream().anyMatch(event -> event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
            && "OWNER gains 1 Miracle through Miracle Creation (6/6 remaining).".equals(event.getMessage())));
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

    private static Path dataFile(String directory, String fileName) throws IOException {
        return List.of(
                Path.of("data", directory, fileName),
                Path.of("..", "data", directory, fileName))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled " + fileName));
    }

    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return true; }
    }
}
