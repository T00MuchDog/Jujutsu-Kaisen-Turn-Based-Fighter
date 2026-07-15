package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectParameter;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilitySystemTest {

    @Test
    void effectDefinitionsExposeOnlyRelevantParametersAndRejectNoOps() {
        AbilityEffectData add = AbilityEffectType.STAT_ADD.createDefault();
        assertTrue(AbilityEffectType.STAT_ADD.uses(AbilityEffectParameter.STAT));
        assertTrue(AbilityEffectType.STAT_ADD.uses(AbilityEffectParameter.INTEGER));
        assertFalse(AbilityEffectType.STAT_ADD.uses(AbilityEffectParameter.DECIMAL));
        assertNull(AbilityEffectType.STAT_ADD.validationError(add));

        add.doubleValue = 5.0;
        AbilityEffectType.STAT_ADD.clearUnusedFields(add);
        assertNull(add.doubleValue);

        add.intValue = 0;
        assertEquals("Enter a non-zero amount.",
            AbilityEffectType.STAT_ADD.validationError(add));

        AbilityEffectData allMoveCosts = AbilityEffectType.CE_COST_TO_MINIMUM.createDefault();
        assertNull(allMoveCosts.moveTag, "A null scope must continue to mean all moves");
        assertNull(AbilityEffectType.CE_COST_TO_MINIMUM.validationError(allMoveCosts));
    }

    @Test
    void everyEffectTypeProvidesAValidAuthoringDefault() {
        for (AbilityEffectType type : AbilityEffectType.values()) {
            AbilityEffectData effect = type.createDefault();
            if (type == AbilityEffectType.GRANT_MOVE) effect.moveId = "MOVE";
            if (type == AbilityEffectType.UNLOCK_TECHNIQUE) effect.stringValue = "Technique";
            assertNull(type.validationError(effect), type.name());
        }
    }

    @Test
    void passiveStatsUseSetThenAddThenMultiplyAndActiveEffectsStayInactive() {
        AbilityData passiveData = ability("PASSIVE", "Passive", "P");
        passiveData.effects = List.of(
            statEffect(AbilityEffectType.STAT_ADD, 10, null),
            statEffect(AbilityEffectType.STAT_MULTIPLY, null, 2.0),
            statEffect(AbilityEffectType.STAT_SET_VALUE, 100, null)
        );

        AbilityData activeData = ability("ACTIVE", "Active", "A");
        activeData.activeSubType = "QUEUED";
        activeData.activeMoveId = "ACTIVE_MOVE";
        activeData.effects = List.of(statEffect(AbilityEffectType.STAT_ADD, 500, null));

        CharacterStats base = new CharacterStats.Builder().strength(80).build();
        AbilityApplicator.ApplicationResult result = AbilityApplicator.apply(
            base, List.of(new Ability(passiveData), new Ability(activeData)));

        assertEquals(220, result.modifiedStats.getStrength());
    }

    @Test
    void oneArgumentCombatantConstructorUsesCharacterPassives() {
        AbilityData passiveData = ability("PASSIVE", "Strong", "P");
        passiveData.effects = List.of(statEffect(AbilityEffectType.STAT_ADD, 25, null));
        Character character = new SorcererCharacter(
            "C", "Character", new CharacterStats.Builder().strength(80).build(),
            null, List.of(), List.of(new Ability(passiveData)));

        BattleCombatant combatant = new BattleCombatant(character);

        assertEquals(105, combatant.getEffectiveStats().getStrength());
    }

    @Test
    void resolverFollowsMoveTechniqueStatAndAbilitySourceChains() {
        AbilityData parent = ability("PASSIVE", "Parent", "A");
        parent.sourceType = "CHARACTER";
        AbilityEffectData grantMove = AbilityEffectType.GRANT_MOVE.createDefault();
        grantMove.moveId = "MOVE_X";
        AbilityEffectData unlockTechnique = AbilityEffectType.UNLOCK_TECHNIQUE.createDefault();
        unlockTechnique.stringValue = "Limitless";
        parent.effects = List.of(grantMove, unlockTechnique);

        AbilityData technique = ability("PASSIVE", "Technique Child", "B");
        technique.sourceType = "TECHNIQUE";
        technique.sourceValue = "Limitless";
        technique.masteryThreshold = 50;

        AbilityData move = ability("PASSIVE", "Move Child", "C");
        move.sourceType = "MOVE";
        move.sourceValue = "MOVE_X";

        AbilityData stat = ability("PASSIVE", "Stat Child", "D");
        stat.sourceType = "STAT_THRESHOLD";
        stat.sourceValue = "strength>=80";

        AbilityData dependency = ability("PASSIVE", "Dependency Child", "E");
        dependency.sourceType = "ABILITY";
        dependency.sourceValue = "B";

        com.jjktbf.model.character.CharacterData character =
            new com.jjktbf.model.character.CharacterData();
        character.strength = 80;
        character.cursedTechniqueMastery = 60;
        character.abilityIds = List.of("A");
        character.moveIds = List.of();

        AbilityResolver.Result result = AbilityResolver.resolve(
            character, List.of(dependency, move, technique, stat, parent));
        Set<String> resolvedIds = new HashSet<>(
            result.abilities().stream().map(ability -> ability.id).toList());

        assertEquals(Set.of("A", "B", "C", "D", "E"), resolvedIds);
        assertEquals(List.of("MOVE_X"), result.grantedMoveIds());
        assertTrue(result.hasTechnique("LIMITLESS"));
    }

    @Test
    void queuedActiveMoveBypassesNormalLearningRequirements() {
        Move restricted = new Move.Builder("ACTIVE_MOVE")
            .name("Active Move")
            .category(MoveCategory.INNATE_TECHNIQUE)
            .requiredTechniqueId("Missing Technique")
            .prerequisites(java.util.Map.of(
                "strength", 300,
                "cursedTechniqueMastery", 300))
            .basePower(10)
            .apCost(10)
            .unleashPoint(1)
            .build();
        AbilityData activeData = ability("ACTIVE", "Move-backed Active", "ACTIVE");
        activeData.activeSubType = "QUEUED";
        activeData.activeMoveId = restricted.getId();

        assertDoesNotThrow(() -> new SorcererCharacter(
            "C", "Character", new CharacterStats.Builder().strength(80).build(),
            null, List.of(restricted), List.of(new Ability(activeData))));
    }

    @Test
    void characterDataAddsTheMoveLinkedByAnAcquiredActiveAbility() {
        Move activeMove = new Move.Builder("SOURCE_ID")
            .name("Active Move")
            .category(MoveCategory.PHYSICAL)
            .prerequisites(java.util.Map.of("strength", 300))
            .basePower(10)
            .apCost(10)
            .unleashPoint(1)
            .build();
        com.jjktbf.model.move.MoveRepository moveRepository =
            new com.jjktbf.model.move.MoveRepository("data/test-ability-moves");
        com.jjktbf.model.move.MoveData moveData =
            com.jjktbf.model.move.MoveData.fromMove(activeMove);
        moveRepository.add(moveData);

        AbilityData activeData = ability("ACTIVE", "Move-backed Active", null);
        activeData.activeSubType = "QUEUED";
        activeData.activeMoveId = moveData.id;
        com.jjktbf.model.character.AbilityRepository abilityRepository =
            new com.jjktbf.model.character.AbilityRepository("data/test-active-abilities");
        abilityRepository.add(activeData);

        com.jjktbf.model.character.CharacterData characterData =
            new com.jjktbf.model.character.CharacterData();
        characterData.id = "CHARACTER";
        characterData.name = "Character";
        characterData.strength = 80;
        characterData.moveIds = List.of();
        characterData.abilityIds = List.of(activeData.id);

        Character character = characterData.toCharacter(moveRepository, abilityRepository);

        assertEquals(List.of(moveData.id),
            character.getKnownMoves().stream().map(Move::getId).toList());
    }

    @Test
    void automaticStatusesApplyAtFightStartAndOnHit() {
        AbilityEffectData fightStart = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        fightStart.stringValue = StatusEffectType.FOCUS.name();
        fightStart.target = AbilityEffectTarget.SELF.name();
        fightStart.timing = AbilityEffectTiming.FIGHT_START.name();
        fightStart.durationRounds = -1;
        fightStart.magnitude = 0.15;

        AbilityEffectData onHit = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        onHit.stringValue = StatusEffectType.CE_OUTPUT_UP.name();
        onHit.target = AbilityEffectTarget.ENEMY.name();
        onHit.timing = AbilityEffectTiming.ON_HIT.name();
        onHit.durationRounds = 1;
        onHit.magnitude = -10.0;

        AbilityData passiveData = ability("PASSIVE", "Status Passive", "STATUS");
        passiveData.effects = List.of(fightStart, onHit);

        Move attack = new Move.Builder("ATTACK")
            .name("Attack")
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(5)
            .unleashPoint(1)
            .build();
        Character attackerCharacter = new SorcererCharacter(
            "A", "Attacker", new CharacterStats.Builder().speed(120).build(),
            null, List.of(attack), List.of(new Ability(passiveData)));
        Character defenderCharacter = new SorcererCharacter(
            "D", "Defender", new CharacterStats.Builder().cursedEnergyOutput(80).build(),
            null, List.of());
        BattleCombatant attacker = new BattleCombatant(attackerCharacter);
        BattleCombatant defender = new BattleCombatant(defenderCharacter);
        Timeline attackerTimeline = new Timeline(10);
        attackerTimeline.placeAt(attack, 1, 0);
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(new Timeline(10));

        BattleState state = new BattleState(attacker, defender);
        assertEquals(0.15, attacker.getStatusBaseAccuracyBonus(), 0.0001);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new FixedRandom()).resolveRound(state);

        assertEquals(70, defender.getEffectiveStats().getCursedEnergyOutput());
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_APPLIED
                && event.getMessage().contains("CE_OUTPUT_UP")));
    }

    @Test
    void roundStartAbilityCostIsChargedBeforePlanningAndOnlyOnce() {
        AbilityEffectData roundCost = AbilityEffectType.COST_CE_PER_ROUND.createDefault();
        roundCost.intValue = 10;
        AbilityData passiveData = ability("PASSIVE", "Upkeep", "UPKEEP");
        passiveData.effects = List.of(roundCost);

        Character ownerCharacter = new SorcererCharacter(
            "O", "Owner", new CharacterStats.Builder().build(),
            null, List.of(), List.of(new Ability(passiveData)));
        Character enemyCharacter = new SorcererCharacter(
            "E", "Enemy", new CharacterStats.Builder().build(), null, List.of());
        BattleCombatant owner = new BattleCombatant(ownerCharacter);
        BattleCombatant enemy = new BattleCombatant(enemyCharacter);
        owner.setTimeline(new Timeline(1));
        enemy.setTimeline(new Timeline(1));
        BattleState state = new BattleState(owner, enemy);
        CombatResolver resolver = new CombatResolver(new FixedRandom());
        int before = owner.getCurrentCe();

        List<CombatEvent> planningEvents = resolver.processRoundStart(state);
        assertEquals(before - 10, owner.getCurrentCe());
        assertEquals(1, planningEvents.stream()
            .filter(event -> event.getType() == CombatEvent.Type.CE_DRAINED)
            .count());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        assertTrue(resolver.beginResolution(state).isEmpty());
        assertEquals(before - 10, owner.getCurrentCe());
    }

    @Test
    void repositoryAwareResolutionIgnoresMissingMoveReferences() {
        AbilityData active = ability("ACTIVE", "Broken Active", "ACTIVE");
        active.activeSubType = "QUEUED";
        active.activeMoveId = "MISSING";

        AbilityData moveSourced = ability("PASSIVE", "Move Source", "MOVE_SOURCE");
        moveSourced.sourceType = "MOVE";
        moveSourced.sourceValue = "MISSING";

        com.jjktbf.model.character.CharacterData character =
            new com.jjktbf.model.character.CharacterData();
        character.abilityIds = List.of("ACTIVE");
        character.moveIds = List.of("MISSING");

        AbilityResolver.Result result = AbilityResolver.resolve(
            character, List.of(active, moveSourced), ignored -> false);

        assertTrue(result.grantedMoveIds().isEmpty());
        assertFalse(result.containsAbility("MOVE_SOURCE"));
    }

    @Test
    void derivedAbilityHelpersAreNotWrittenToJson() throws Exception {
        AbilityData data = ability("PASSIVE", "Serialized", "S");
        data.effects = List.of(AbilityEffectType.STAT_SET_MIN.createDefault());

        String json = new ObjectMapper().writeValueAsString(data);

        assertFalse(json.contains("\"passive\""));
        assertFalse(json.contains("\"active\""));
        assertFalse(json.contains("\"queued\""));
        assertFalse(json.contains("\"triggered\""));
    }

    @Test
    void bundledAbilitiesContainValidPassiveEffects() throws IOException {
        Path path = List.of(
                Path.of("data", "abilities", "all_abilities.json"),
                Path.of("..", "data", "abilities", "all_abilities.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled ability data"));
        List<AbilityData> abilities = new ObjectMapper().readValue(
            path.toFile(), new TypeReference<List<AbilityData>>() { });

        assertFalse(abilities.isEmpty());
        for (AbilityData ability : abilities) {
            assertTrue(ability.isPassive() || ability.isActive(), ability.name);
            if (ability.isActive()) continue;
            assertFalse(ability.effects == null || ability.effects.isEmpty(), ability.name);
            for (AbilityEffectData effect : ability.effects) {
                AbilityEffectType type = AbilityEffectType.fromName(effect.type);
                assertNull(type.validationError(effect), ability.name + ": " + type.name());
            }
        }
    }

    private static AbilityData ability(String category, String name, String id) {
        AbilityData data = new AbilityData();
        data.id = id;
        data.name = name;
        data.category = category;
        data.sourceType = "CHARACTER";
        data.effects = List.of();
        return data;
    }

    private static AbilityEffectData statEffect(
        AbilityEffectType type,
        Integer integer,
        Double decimal
    ) {
        AbilityEffectData effect = type.createDefault();
        effect.stat = "strength";
        effect.intValue = integer;
        effect.doubleValue = decimal;
        type.clearUnusedFields(effect);
        return effect;
    }

    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return true; }
    }
}
