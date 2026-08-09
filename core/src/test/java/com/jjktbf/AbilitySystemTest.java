package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityConditionActor;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectParameter;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectTiming;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.SeededRandomSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            if (type == AbilityEffectType.GRANT_MOVE
                || type == AbilityEffectType.UNLOCK_MOVE) effect.moveId = "MOVE";
            if (type == AbilityEffectType.GRANT_ABILITY) effect.abilityId = "ABILITY";
            if (type == AbilityEffectType.UNLOCK_TECHNIQUE) effect.stringValue = "Technique";
            if (type == AbilityEffectType.SUMMON_CHARACTER) effect.characterId = "000010";
            assertNull(type.validationError(effect), type.name());
        }
    }

    @Test
    void passiveStatsAlwaysApplyAndActiveEffectsWaitForActivation() {
        AbilityData passiveData = ability("PASSIVE", "Passive", "P");
        passiveData.activationCondition = AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        passiveData.effects = List.of(
            statEffect(AbilityEffectType.STAT_ADD, 10, null),
            statEffect(AbilityEffectType.STAT_MULTIPLY, null, 2.0),
            statEffect(AbilityEffectType.STAT_SET_VALUE, 100, null)
        );

        AbilityData activeData = ability("ACTIVE", "Active", "A");
        activeData.activationCondition = AbilityConditionData.manualActivation();
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
    void codedAndGenericEffectsCanCoexistOnOneAbility() {
        AbilityEffectData coded = AbilityEffectType.CODED.createDefault();
        coded.codedAbilityKey = MiraclesAbility.KEY;
        coded.codedFeature = MiraclesAbility.RESERVOIR;
        AbilityData data = ability("PASSIVE", "Mutable Coded Ability", "CODED");
        data.effects = List.of(
            coded,
            statEffect(AbilityEffectType.STAT_ADD, 25, null)
        );

        BattleCombatant combatant = combatant(
            "OWNER", List.of(), List.of(new Ability(data)));

        assertEquals(105, combatant.getEffectiveStats().getStrength());
        assertTrue(combatant.getCodedAbilities().states().stream()
            .anyMatch(state -> MiraclesAbility.KEY.equals(state.key())));
    }

    @Test
    void resolverSeparatesAvailableContentFromAssignedContent() {
        AbilityData parent = ability("PASSIVE", "Parent", "A");
        parent.sourceType = "CHARACTER";
        AbilityEffectData grantMove = AbilityEffectType.UNLOCK_MOVE.createDefault();
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
        character.abilityIds = List.of("A", "B");
        character.moveIds = List.of();

        AbilityResolver.Result result = AbilityResolver.resolve(
            character, List.of(dependency, move, technique, stat, parent));
        Set<String> resolvedIds = new HashSet<>(
            result.abilities().stream().map(ability -> ability.id).toList());

        assertEquals(Set.of("A", "B"), resolvedIds);
        assertTrue(result.availableAbilityIds().containsAll(Set.of("A", "B", "D", "E")));
        assertFalse(result.availableAbilityIds().contains("C"),
            "A granted move does not satisfy a source that requires knowing the move");
        assertEquals(List.of("MOVE_X"), result.availableMoveIds());
        assertTrue(result.grantedMoveIds().isEmpty());
        assertTrue(result.hasTechnique("LIMITLESS"));
    }

    @Test
    void grantAbilityMakesTargetAvailableWithoutAssigningIt() {
        AbilityData parent = ability("PASSIVE", "Parent", "A");
        AbilityEffectData grant = AbilityEffectType.GRANT_ABILITY.createDefault();
        grant.abilityId = "B";
        parent.effects = List.of(grant);

        AbilityData child = ability("PASSIVE", "Child", "B");
        child.sourceType = "MOVE";
        child.sourceValue = "UNLEARNED";

        com.jjktbf.model.character.CharacterData character =
            new com.jjktbf.model.character.CharacterData();
        character.abilityIds = List.of("A");
        character.moveIds = List.of();

        AbilityResolver.Result result = AbilityResolver.resolve(character, List.of(parent, child));

        assertTrue(result.containsAbility("A"));
        assertFalse(result.containsAbility("B"));
        assertTrue(result.availableAbilityIds().contains("B"));
    }

    @Test
    void grantMoveBypassesRequirementsWhileUnlockMoveOnlyMakesItAvailable() {
        AbilityData ability = ability("PASSIVE", "Acquisition", "A");
        AbilityEffectData unlock = AbilityEffectType.UNLOCK_MOVE.createDefault();
        unlock.moveId = "UNLOCKED";
        AbilityEffectData grant = AbilityEffectType.GRANT_MOVE.createDefault();
        grant.moveId = "GRANTED";
        ability.effects = List.of(unlock, grant);

        AbilityData techniqueAbility = ability("PASSIVE", "Technique Acquisition", "T");
        techniqueAbility.sourceType = "TECHNIQUE";
        techniqueAbility.sourceValue = "Technique";
        AbilityEffectData techniqueGrant = AbilityEffectType.GRANT_MOVE.createDefault();
        techniqueGrant.moveId = "TECHNIQUE_GRANTED";
        techniqueAbility.effects = List.of(techniqueGrant);

        com.jjktbf.model.character.CharacterData character =
            new com.jjktbf.model.character.CharacterData();
        character.innateTechniqueName = "Technique";
        character.abilityIds = List.of("A", "T");
        character.moveIds = List.of();

        AbilityResolver.Result result = AbilityResolver.resolve(
            character, List.of(ability, techniqueAbility));

        // Both effects make the move available, and neither auto-learns it.
        assertTrue(result.availableMoveIds().containsAll(
            Set.of("UNLOCKED", "GRANTED", "TECHNIQUE_GRANTED")));
        // Only GRANT_MOVE-granted moves bypass requirements; UNLOCK_MOVE does not.
        assertEquals(List.of("GRANTED", "TECHNIQUE_GRANTED"), result.grantedMoveIds());
    }

    @Test
    void activeAbilitiesDoNotInjectOrForceMoves() {
        AbilityData activeData = ability("ACTIVE", "Conditional Active", "ACTIVE");
        activeData.activationCondition = AbilityConditionData.manualActivation();
        activeData.effects = List.of(AbilityEffectType.HEAL_HP.createDefault());

        Character character = new SorcererCharacter(
            "C", "Character", new CharacterStats.Builder().strength(80).build(),
            null, List.of(), List.of(new Ability(activeData)));

        assertTrue(character.getKnownMoves().isEmpty());
    }

    @Test
    void grantMoveBypassesPrerequisitesWhenMoveIsAssigned() {
        Move grantedMove = new Move.Builder("SOURCE_ID")
            .name("Granted Move")
            .category(MoveCategory.PHYSICAL)
            .mustBeGranted(true)
            .prerequisites(java.util.Map.of("strength", 300))
            .basePower(10)
            .apCost(10)
            .unleashPoint(1)
            .build();
        com.jjktbf.model.move.MoveRepository moveRepository =
            new com.jjktbf.model.move.MoveRepository("data/test-grant-moves");
        com.jjktbf.model.move.MoveData moveData =
            com.jjktbf.model.move.MoveData.fromMove(grantedMove);
        moveRepository.add(moveData);

        AbilityData ability = ability("PASSIVE", "Grant", null);
        AbilityEffectData grant = AbilityEffectType.GRANT_MOVE.createDefault();
        grant.moveId = moveData.id;
        ability.effects = List.of(grant);
        com.jjktbf.model.character.AbilityRepository abilityRepository =
            new com.jjktbf.model.character.AbilityRepository("data/test-grant-abilities");
        abilityRepository.add(ability);

        com.jjktbf.model.character.CharacterData characterData =
            new com.jjktbf.model.character.CharacterData();
        characterData.id = "CHARACTER";
        characterData.name = "Character";
        characterData.strength = 80;
        // GRANT_MOVE no longer auto-learns the move; it must be assigned, but
        // the impossible strength prerequisite (300) is bypassed at validation.
        characterData.moveIds = List.of(moveData.id);
        characterData.abilityIds = List.of(ability.id);

        Character character = characterData.toCharacter(moveRepository, abilityRepository);

        assertEquals(List.of(moveData.id),
            character.getKnownMoves().stream().map(Move::getId).toList());
        // Assigned moves round-trip through persistence now that they are not
        // auto-injected by the ability.
        assertEquals(List.of(moveData.id),
            com.jjktbf.model.character.CharacterData.fromCharacter(character).moveIds);
    }

    @Test
    void automaticStatusesApplyAtFightStartAndOnHit() {
        AbilityEffectData fightStart = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        fightStart.stringValue = StatusEffectType.ACCURACY_INCREASE.name();
        fightStart.target = AbilityEffectTarget.SELF.name();
        fightStart.timing = AbilityEffectTiming.FIGHT_START.name();
        fightStart.durationRounds = -1;
        fightStart.magnitude = 15.0;

        AbilityEffectData onHit = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        onHit.stringValue = StatusEffectType.CURSED_ENERGY_OUTPUT_DECREASE.name();
        onHit.target = AbilityEffectTarget.ENEMY.name();
        onHit.timing = AbilityEffectTiming.ON_HIT.name();
        onHit.durationRounds = 1;
        onHit.magnitude = 10.0;

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
        assertEquals(attacker.getEffectiveCombatStats().getAccuracy() + 15,
            attacker.getAccuracy());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new FixedRandom()).resolveRound(state);

        assertEquals(70, defender.getEffectiveStats().getCursedEnergyOutput());
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_APPLIED
                && event.getMessage().contains("Decrease Cursed Energy Output")));
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
    void repositoryAwareResolutionIgnoresMissingMoveSources() {
        AbilityData active = ability("ACTIVE", "Broken Active", "ACTIVE");
        active.activationCondition = AbilityConditionData.manualActivation();

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
        assertFalse(result.availableAbilityIds().contains("MOVE_SOURCE"));
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
        assertFalse(json.contains("\"activeMoveId\""));
        assertFalse(json.contains("\"activeSubType\""));
    }

    @Test
    void codedBindingsSerializeInsideEffectRowsOnly() {
        AbilityEffectData coded = AbilityEffectType.CODED.createDefault();
        coded.codedAbilityKey = MiraclesAbility.KEY;
        coded.codedFeature = MiraclesAbility.FATEFUL_REPRIEVE;
        AbilityData data = ability("ACTIVE", "Coded", "CODED");
        data.effects = List.of(coded);

        com.fasterxml.jackson.databind.JsonNode json =
            new ObjectMapper().valueToTree(data);

        assertNull(json.get("codedAbilityKey"));
        assertNull(json.get("codedFeature"));
        assertEquals("CODED", json.path("effects").get(0).path("type").asText());
        assertEquals(MiraclesAbility.KEY,
            json.path("effects").get(0).path("codedAbilityKey").asText());
        assertEquals(MiraclesAbility.FATEFUL_REPRIEVE,
            json.path("effects").get(0).path("codedFeature").asText());
    }

    @Test
    void bundledAbilitiesContainValidEffects() throws IOException {
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
            assertNotNull(ability.effects, ability.name);
            if (ability.isActive()) assertFalse(ability.effects.isEmpty(), ability.name);
            Set<String> effectIds = new HashSet<>();
            for (AbilityEffectData effect : ability.effects) {
                assertTrue(effect.effectId != null && !effect.effectId.isBlank(), ability.name);
                assertTrue(effectIds.add(effect.effectId), ability.name + " duplicate effect ID");
            }
            if (ability.isActive()) {
                assertNull(AbilityConditionRuleData.validationError(
                    ability.activationConditions, ability.effects), ability.name);
            }
            for (AbilityEffectData effect : ability.effects) {
                AbilityEffectType type = AbilityEffectType.fromName(effect.type);
                assertNull(type.validationError(effect), ability.name + ": " + type.name());
                if (effect.isCoded()) {
                    assertTrue(CodedAbilityRegistry.supportsAbilityEffect(
                        effect.codedAbilityKey, effect.codedFeature), ability.name);
                }
            }
        }
    }

    @Test
    void conditionTreesValidateNestedLogicAndAlwaysIsExclusive() {
        AbilityConditionData moveUsed = AbilityConditionType.MOVE_USED.createDefault();
        moveUsed.moveId = "MOVE";
        AbilityConditionData missed = AbilityConditionType.ATTACK_MISSED.createDefault();
        missed.actor = AbilityConditionActor.ENEMY.name();
        AbilityConditionData nested = AbilityConditionData.all(List.of(
            moveUsed,
            AbilityConditionData.any(List.of(missed, AbilityConditionType.ROUND_REACHED.createDefault()))
        ));

        assertNull(AbilityConditionType.validationError(nested));

        AbilityConditionData invalid = AbilityConditionData.any(List.of(
            AbilityConditionData.always(), moveUsed));
        assertEquals("Always active cannot be combined with another condition.",
            AbilityConditionType.validationError(invalid));
    }

    @Test
    void manualActivationIsValidButDoesNotFireAutomatically() {
        AbilityConditionData manual = AbilityConditionType.MANUAL_ACTIVATION.createDefault();
        assertNull(AbilityConditionType.validationError(manual));

        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 20;
        AbilityData data = ability("ACTIVE", "Manual", "MANUAL");
        data.activationCondition = manual;
        data.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(50);
        int before = owner.getCurrentHp();
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(new FixedRandom()));

        assertTrue(engine.process(new BattleState(owner, enemy),
            AbilityTrigger.simple(AbilityTrigger.Type.BATTLE_START)).isEmpty());
        assertEquals(before, owner.getCurrentHp());

        List<CombatEvent> events = new CombatResolver(new FixedRandom())
            .activateAbilityManually(new BattleState(owner, enemy), owner, data.id, 1);
        assertEquals(before + 20, owner.getCurrentHp());
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED));

        AbilityData automatic = ability("ACTIVE", "Automatic", "AUTOMATIC");
        AbilityConditionData hp = AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        hp.percentage = 0.9;
        automatic.activationCondition = hp;
        automatic.effects = List.of(heal.copy());
        BattleCombatant automaticOwner = combatant(
            "AUTOMATIC_OWNER", List.of(), List.of(new Ability(automatic)));
        automaticOwner.applyDamage(50);
        int automaticHp = automaticOwner.getCurrentHp();
        BattleCombatant automaticEnemy = combatant("AUTOMATIC_ENEMY", List.of(), List.of());
        assertTrue(new CombatResolver(new FixedRandom()).activateAbilityManually(
            new BattleState(automaticOwner, automaticEnemy),
            automaticOwner, automatic.id, 1).isEmpty());
        assertEquals(automaticHp, automaticOwner.getCurrentHp());
    }

    @Test
    void conditionRulesActivateOnlyTheirLinkedEffects() {
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.effectId = "effect-heal";
        heal.intValue = 20;
        AbilityEffectData restore = AbilityEffectType.RESTORE_CE.createDefault();
        restore.effectId = "effect-ce";
        restore.intValue = 15;

        AbilityConditionRuleData opening = AbilityConditionRuleData.allEffects(
            AbilityConditionType.BATTLE_STARTED.createDefault());
        opening.targetEffectIds = List.of(heal.effectId);
        AbilityConditionRuleData onHit = AbilityConditionRuleData.allEffects(
            AbilityConditionType.ATTACK_HIT.createDefault());
        onHit.targetEffectIds = List.of(restore.effectId);

        AbilityData data = ability("ACTIVE", "Linked effects", "LINKED");
        data.effects = List.of(heal, restore);
        data.activationConditions = List.of(opening, onHit);
        assertNull(AbilityConditionRuleData.validationError(
            data.activationConditions, data.effects));

        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(50);
        owner.drainCe(30);
        int damagedHp = owner.getCurrentHp();
        int drainedCe = owner.getCurrentCe();
        BattleState state = new BattleState(owner, enemy);
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(new FixedRandom()));

        engine.process(state, AbilityTrigger.simple(AbilityTrigger.Type.BATTLE_START));
        assertEquals(damagedHp + 20, owner.getCurrentHp());
        assertEquals(drainedCe, owner.getCurrentCe());

        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, owner, enemy, attack("LINKED_HIT"), 1));
        assertEquals(damagedHp + 20, owner.getCurrentHp());
        assertEquals(drainedCe + 15, owner.getCurrentCe());
    }

    @Test
    void failedCompoundManualRequestDoesNotArmALaterAutomaticActivation() {
        AbilityConditionData hp = AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        hp.percentage = 0.5;
        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(
            AbilityConditionData.all(List.of(
                AbilityConditionData.manualActivation(), hp)));
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.effectId = "heal";
        heal.intValue = 20;
        rule.targetEffectIds = List.of(heal.effectId);
        AbilityData data = ability("ACTIVE", "Manual threshold", "MANUAL_THRESHOLD");
        data.effects = List.of(heal);
        data.activationConditions = List.of(rule);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);

        assertTrue(new CombatResolver(new FixedRandom()).activateAbilityManually(
            state, owner, data.id, 0).isEmpty());
        owner.applyDamage(owner.getMaxHp() * 3 / 4);
        int before = owner.getCurrentHp();

        new AbilityActivationEngine(new SeededRandomSource(new FixedRandom())).process(
            state, AbilityTrigger.phase(BattleState.Phase.PLANNING));
        assertEquals(before, owner.getCurrentHp());
    }

    @Test
    void legacyActivationDataMigratesToOneAllEffectsRule() {
        AbilityData data = ability("ACTIVE", "Legacy", "LEGACY");
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        data.effects = new java.util.ArrayList<>(List.of(heal));
        data.activationCondition = AbilityConditionType.ATTACK_HIT.createDefault();
        data.activationChanceEnabled = true;
        data.activationChance = 0.25;

        data.migrateActivationData();

        assertEquals("effect-000000", heal.effectId);
        assertEquals(1, data.activationConditions.size());
        AbilityConditionRuleData rule = data.activationConditions.get(0);
        assertEquals(AbilityConditionType.ATTACK_HIT.name(), rule.condition.type);
        assertNull(rule.targetEffectIds);
        assertEquals(0.25, rule.activationChance);
        assertNull(data.activationCondition);
        assertNull(data.activationChanceEnabled);
        assertNull(data.activationChance);
    }

    @Test
    void legacyCodedAbilitiesRecoverTheirFormerAutomaticConditions() {
        AbilityData ratio = ability("ACTIVE", "Legacy Ratio", "LEGACY_RATIO");
        AbilityEffectData coded = AbilityEffectType.CODED.createDefault();
        coded.codedAbilityKey = "RATIO";
        coded.codedFeature = "REINFORCEMENT_RATIO";
        ratio.effects = new java.util.ArrayList<>(List.of(coded));

        ratio.migrateActivationData();

        assertEquals("effect-000000", coded.effectId);
        assertEquals(1, ratio.activationConditions.size());
        AbilityConditionRuleData rule = ratio.activationConditions.get(0);
        assertEquals(AbilityConditionType.ALL.name(), rule.condition.type);
        assertEquals(List.of(coded.effectId), rule.targetEffectIds);
        assertTrue(Boolean.TRUE.equals(rule.matchSameTrigger));
        assertTrue(Boolean.TRUE.equals(rule.activationChanceEnabled));
        assertEquals(0.05, rule.activationChance);
        assertNull(AbilityConditionRuleData.validationError(
            ratio.activationConditions, ratio.effects));
    }

    @Test
    void legacyMixedAbilitySeparatesGenericAndCodedActivationRules() {
        AbilityData data = ability("ACTIVE", "Legacy mixed", "LEGACY_MIXED");
        AbilityEffectData coded = AbilityEffectType.CODED.createDefault();
        coded.codedAbilityKey = "RATIO";
        coded.codedFeature = "REINFORCEMENT_RATIO";
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        data.effects = new java.util.ArrayList<>(List.of(coded, heal));
        data.activationCondition = AbilityConditionType.ATTACK_HIT.createDefault();
        data.activationChanceEnabled = true;
        data.activationChance = 0.25;

        data.migrateActivationData();

        assertEquals(2, data.activationConditions.size());
        AbilityConditionRuleData generic = data.activationConditions.get(0);
        AbilityConditionRuleData codedRule = data.activationConditions.get(1);
        assertEquals(List.of(heal.effectId), generic.targetEffectIds);
        assertEquals(AbilityConditionType.ATTACK_HIT.name(), generic.condition.type);
        assertEquals(0.25, generic.activationChance);
        assertEquals(List.of(coded.effectId), codedRule.targetEffectIds);
        assertEquals(0.05, codedRule.activationChance);
        assertNull(AbilityConditionRuleData.validationError(
            data.activationConditions, data.effects));
    }

    @Test
    void malformedOrUnsupportedConditionLinksFailValidation() {
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.effectId = "heal";
        AbilityConditionRuleData missing = new AbilityConditionRuleData();
        missing.targetEffectIds = List.of(heal.effectId);
        assertEquals("Condition 1 predicate is missing.",
            AbilityConditionRuleData.validationError(List.of(missing), List.of(heal)));

        AbilityConditionRuleData fatalHeal = AbilityConditionRuleData.allEffects(
            AbilityConditionType.FATAL_DAMAGE.createDefault());
        fatalHeal.targetEffectIds = List.of(heal.effectId);
        assertEquals(
            "Condition 1 uses a pre-resolution condition that can only target coded effects.",
            AbilityConditionRuleData.validationError(List.of(fatalHeal), List.of(heal)));

        AbilityEffectData ratio = AbilityEffectType.CODED.createDefault();
        ratio.effectId = "ratio";
        ratio.codedAbilityKey = "RATIO";
        ratio.codedFeature = "REINFORCEMENT_RATIO";
        AbilityConditionRuleData fatalRatio = AbilityConditionRuleData.allEffects(
            AbilityConditionType.FATAL_DAMAGE.createDefault());
        fatalRatio.targetEffectIds = List.of(ratio.effectId);
        assertEquals(
            "Condition 1: Fatal damage incoming is not a runtime opportunity for "
                + "RATIO/REINFORCEMENT_RATIO.",
            AbilityConditionRuleData.validationError(List.of(fatalRatio), List.of(ratio)));
    }

    @Test
    void intersectedEventConditionsCanCompleteAcrossDistinctBattleEvents() {
        Move attack = attack("ATTACK");
        AbilityConditionData usedMove = AbilityConditionType.MOVE_USED.createDefault();
        usedMove.moveId = attack.getId();
        AbilityConditionData hit = AbilityConditionType.ATTACK_HIT.createDefault();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 20;

        AbilityData data = ability("ACTIVE", "Sequence", "SEQUENCE");
        data.activationCondition = AbilityConditionData.all(List.of(usedMove, hit));
        data.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(attack), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(50);
        int damagedHp = owner.getCurrentHp();
        BattleState state = new BattleState(owner, enemy);
        AbilityActivationEngine engine = new AbilityActivationEngine(new SeededRandomSource(new FixedRandom()));

        assertTrue(engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.MOVE_USED, owner, enemy, attack, 1)).isEmpty());
        assertEquals(damagedHp, owner.getCurrentHp());

        List<CombatEvent> events = engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, owner, enemy, attack, 1));
        assertEquals(damagedHp + 20, owner.getCurrentHp());
        assertTrue(events.stream().anyMatch(event -> event.getType() == CombatEvent.Type.ABILITY_ACTIVATED));

        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, owner, enemy, attack, 2));
        assertEquals(damagedHp + 20, owner.getCurrentHp(), "The consumed move fact must be required again");
    }

    @Test
    void stateThresholdsAreEdgeTriggeredAndChanceCanDisableAnActivation() {
        AbilityConditionData hp = AbilityConditionType.HP_PERCENT_AT_OR_BELOW.createDefault();
        hp.percentage = 0.75;
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;

        AbilityData threshold = ability("ACTIVE", "Threshold", "THRESHOLD");
        threshold.activationCondition = hp;
        threshold.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(threshold)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        AbilityActivationEngine engine = new AbilityActivationEngine(new SeededRandomSource(new FixedRandom()));
        owner.applyDamage(owner.getMaxHp() / 2);
        int before = owner.getCurrentHp();

        engine.process(state, AbilityTrigger.phase(BattleState.Phase.RESOLUTION));
        assertEquals(before + 10, owner.getCurrentHp());
        engine.process(state, AbilityTrigger.tick(1));
        assertEquals(before + 10, owner.getCurrentHp(), "A continuously true threshold must not fire every tick");

        AbilityData impossibleChance = ability("ACTIVE", "No proc", "NO_PROC");
        impossibleChance.activationCondition = AbilityConditionType.ATTACK_HIT.createDefault();
        impossibleChance.activationChanceEnabled = true;
        impossibleChance.activationChance = 0.0;
        impossibleChance.effects = List.of(heal);
        BattleCombatant noProc = combatant("NO_PROC", List.of(), List.of(new Ability(impossibleChance)));
        noProc.applyDamage(50);
        BattleState chanceState = new BattleState(noProc, enemy);
        int chanceHp = noProc.getCurrentHp();
        engine.process(chanceState, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, noProc, enemy, attack("CHANCE_ATTACK"), 1));
        assertEquals(chanceHp, noProc.getCurrentHp());
    }

    @Test
    void modularDefensesAndPlanningEffectsApplyAndExpire() {
        AbilityEffectData immunity = AbilityEffectType.IGNORE_DAMAGE.createDefault();
        immunity.uses = 1;
        AbilityEffectData shield = AbilityEffectType.DAMAGE_SHIELD.createDefault();
        shield.intValue = 10;
        AbilityEffectData ap = AbilityEffectType.BATTLE_STAT_ADD.createDefault();
        ap.stringValue = BattleStatKey.MAX_AP.name();
        ap.doubleValue = 20.0;
        ap.durationRounds = 1;
        AbilityEffectData lock = AbilityEffectType.TEMP_LOCK_MOVE_TAG.createDefault();
        lock.moveTag = "CURSED_ENERGY";
        lock.target = "SELF";
        lock.durationRounds = 1;

        AbilityData data = ability("ACTIVE", "Battle setup", "SETUP");
        data.activationCondition = AbilityConditionData.always();
        data.effects = List.of(immunity, shield, ap, lock);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        CombatResolver resolver = new CombatResolver(new FixedRandom());
        int baseAp = owner.getEffectiveCombatStats().getMaxApBar();

        resolver.processRoundStart(state);
        assertEquals(baseAp + 20, owner.getMaxApBar());
        assertTrue(owner.getAbilityFlags().lockedMoveTags.contains("CURSED_ENERGY"));
        assertEquals(0, owner.receiveDamage(25));
        assertEquals(5, owner.receiveDamage(15));

        state.transitionTo(BattleState.Phase.ROUND_END);
        resolver.processRoundEnd(state);
        assertEquals(baseAp, owner.getMaxApBar());
        assertFalse(owner.getAbilityFlags().lockedMoveTags.contains("CURSED_ENERGY"));
    }

    @Test
    void runtimeAbilityEffectsCanExpireByTimelineTicks() {
        BattleCombatant owner = combatant("OWNER", List.of(), List.of());
        AbilityEffectData strength = AbilityEffectType.TEMP_STAT_ADD.createDefault();
        strength.stat = com.jjktbf.model.character.StatKey.STRENGTH.fieldName;
        strength.intValue = 10;
        strength.durationRounds = 0;
        strength.durationTicks = 2;

        owner.addRuntimeAbilityEffect(strength);
        assertEquals(90, owner.getEffectiveStats().getStrength());

        owner.tickTimelineEffects();
        assertEquals(90, owner.getEffectiveStats().getStrength());

        owner.tickTimelineEffects();
        assertEquals(80, owner.getEffectiveStats().getStrength());
    }

    @Test
    void removingTheLastTickEffectShortensTheResolutionSweep() {
        AbilityConditionData firstTick =
            AbilityConditionType.TIMELINE_POINT_REACHED.createDefault();
        firstTick.tick = 1;
        AbilityEffectData remove = AbilityEffectType.REMOVE_STATUS.createDefault();
        remove.stringValue = StatusEffectType.STRENGTH_INCREASE.name();
        remove.target = AbilityEffectTarget.SELF.name();
        AbilityData data = ability("ACTIVE", "Remove tick effect", "REMOVE_TICK_EFFECT");
        data.activationCondition = firstTick;
        data.effects = List.of(remove);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 0, 10, 10.0));
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new FixedRandom());

        resolver.beginResolution(state);
        assertTrue(resolver.hasMoreTicks());
        resolver.resolveTick(state);

        assertFalse(owner.hasEffect(StatusEffectType.STRENGTH_INCREASE));
        assertFalse(resolver.hasMoreTicks());
    }

    @Test
    void resolutionPhaseKillStopsTickDurationSweep() {
        AbilityConditionData resolution = AbilityConditionType.PHASE_REACHED.createDefault();
        resolution.phase = BattleState.Phase.RESOLUTION.name();
        AbilityEffectData kill = AbilityEffectType.INSTANT_KILL.createDefault();
        kill.target = AbilityEffectTarget.ENEMY.name();
        AbilityData data = ability("ACTIVE", "Resolution kill", "RESOLUTION_KILL");
        data.activationCondition = resolution;
        data.effects = List.of(kill);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.STRENGTH_INCREASE, 0, 10, 10.0));
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new FixedRandom());

        List<CombatEvent> events = resolver.beginResolution(state);

        assertTrue(state.isBattleOver());
        assertFalse(resolver.hasMoreTicks());
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.BATTLE_OVER)
            .count());
    }

    @Test
    void malformedAbilityDurationFailsAtDomainConstruction() {
        AbilityEffectData effect = AbilityEffectType.TEMP_STAT_ADD.createDefault();
        effect.durationRounds = 0;
        effect.durationTicks = 0;
        AbilityData data = ability("ACTIVE", "Invalid duration", "INVALID_DURATION");
        data.effects = List.of(effect);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> new Ability(data));
    }

    @Test
    void statusTriggeredAbilitiesCannotRecursivelyActivateThemselves() {
        AbilityConditionData statusApplied = AbilityConditionType.STATUS_APPLIED.createDefault();
        statusApplied.statusType = StatusEffectType.STRENGTH_DECREASE.name();
        AbilityEffectData applyStatus = AbilityEffectType.APPLY_STATUS.createDefault();
        applyStatus.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
        applyStatus.target = "SELF";

        AbilityData data = ability("ACTIVE", "Status loop", "STATUS_LOOP");
        data.activationCondition = statusApplied;
        data.effects = List.of(applyStatus);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);

        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(new FixedRandom()));
        List<CombatEvent> events = engine.process(
                state,
                AbilityTrigger.status(AbilityTrigger.Type.STATUS_APPLIED,
                    owner, StatusEffectType.STRENGTH_DECREASE, 1));

        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.ABILITY_ACTIVATED)
            .count());
        assertEquals(1, owner.getActiveEffects().size());
        assertTrue(engine.process(state, AbilityTrigger.phase(
            BattleState.Phase.PLANNING)).stream().noneMatch(event ->
                event.getType() == CombatEvent.Type.ABILITY_ACTIVATED));
    }

    @Test
    void removedNegativeStatusPredicateDoesNotBecomeUnconditional() {
        AbilityConditionData missingStatus =
            AbilityConditionType.DOES_NOT_HAVE_STATUS.createDefault();
        missingStatus.statusType = "REMOVED_STATUS";
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData data = ability("ACTIVE", "Removed status predicate", "REMOVED_STATUS");
        data.activationCondition = missingStatus;
        data.effects = List.of(heal);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(20);
        int before = owner.getCurrentHp();

        new AbilityActivationEngine(new SeededRandomSource(new FixedRandom())).process(
            new BattleState(owner, enemy),
            AbilityTrigger.simple(AbilityTrigger.Type.ROUND_START));

        assertEquals(before, owner.getCurrentHp());
    }

    @Test
    void legacyStatusReferencesMatchEitherMigratedDirection() {
        AbilityConditionData hasFocus = AbilityConditionType.HAS_STATUS.createDefault();
        hasFocus.statusType = "FOCUS";
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData predicateData = ability("ACTIVE", "Legacy focus predicate", "LEGACY_HAS_FOCUS");
        predicateData.activationCondition = hasFocus;
        predicateData.effects = List.of(heal);
        BattleCombatant predicateOwner = combatant(
            "PREDICATE_OWNER", List.of(), List.of(new Ability(predicateData)));
        BattleCombatant predicateEnemy = combatant("PREDICATE_ENEMY", List.of(), List.of());
        predicateOwner.applyDamage(20);
        predicateOwner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.ACCURACY_DECREASE, 1, 10.0));
        int predicateHp = predicateOwner.getCurrentHp();

        new AbilityActivationEngine(new SeededRandomSource(new FixedRandom())).process(
            new BattleState(predicateOwner, predicateEnemy),
            AbilityTrigger.simple(AbilityTrigger.Type.ROUND_START));

        assertEquals(predicateHp + 10, predicateOwner.getCurrentHp());

        AbilityConditionData focusApplied = AbilityConditionType.STATUS_APPLIED.createDefault();
        focusApplied.statusType = "FOCUS";
        AbilityData eventData = ability("ACTIVE", "Legacy focus event", "LEGACY_FOCUS_EVENT");
        eventData.activationCondition = focusApplied;
        eventData.effects = List.of(heal.copy());
        BattleCombatant eventOwner = combatant(
            "EVENT_OWNER", List.of(), List.of(new Ability(eventData)));
        BattleCombatant eventEnemy = combatant("EVENT_ENEMY", List.of(), List.of());
        eventOwner.applyDamage(20);
        int eventHp = eventOwner.getCurrentHp();

        new AbilityActivationEngine(new SeededRandomSource(new FixedRandom())).process(
            new BattleState(eventOwner, eventEnemy),
            AbilityTrigger.status(AbilityTrigger.Type.STATUS_APPLIED,
                eventOwner, StatusEffectType.ACCURACY_DECREASE, 1));

        assertEquals(eventHp + 10, eventOwner.getCurrentHp());
    }

    @Test
    void legacyRemoveStatusReferenceClearsBothMigratedDirections() {
        AbilityEffectData removeFocus = AbilityEffectType.REMOVE_STATUS.createDefault();
        removeFocus.stringValue = "FOCUS";
        removeFocus.target = AbilityEffectTarget.SELF.name();
        AbilityData data = ability("ACTIVE", "Remove legacy focus", "REMOVE_LEGACY_FOCUS");
        data.activationCondition = AbilityConditionData.always();
        data.effects = List.of(removeFocus);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.ACCURACY_INCREASE, 1, 10.0));
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.ACCURACY_DECREASE, 1, 5.0));

        List<CombatEvent> events = new AbilityActivationEngine(
            new SeededRandomSource(new FixedRandom())).process(
                new BattleState(owner, enemy),
                AbilityTrigger.simple(AbilityTrigger.Type.ROUND_START));

        assertFalse(owner.hasEffect(StatusEffectType.ACCURACY_INCREASE));
        assertFalse(owner.hasEffect(StatusEffectType.ACCURACY_DECREASE));
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.STATUS_EXPIRED)
            .count());
    }

    @Test
    void activeInstantKillEndsTheBattleBeforePlanningContinues() {
        AbilityEffectData kill = AbilityEffectType.INSTANT_KILL.createDefault();
        kill.target = "ENEMY";
        AbilityData data = ability("ACTIVE", "Opening kill", "OPENING_KILL");
        data.activationCondition = AbilityConditionData.always();
        data.effects = List.of(kill);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);

        List<CombatEvent> events = new CombatResolver(new FixedRandom()).processRoundStart(state);

        assertTrue(state.isBattleOver());
        assertEquals(owner, state.getWinner());
        assertTrue(events.stream().anyMatch(event -> event.getType() == CombatEvent.Type.BATTLE_OVER));
    }

    @Test
    void planningEffectTriggeredDuringResolutionSurvivesNextPlanningWindow() {
        AbilityConditionData hit = AbilityConditionType.ATTACK_HIT.createDefault();
        AbilityEffectData lock = AbilityEffectType.TEMP_LOCK_MOVE_TAG.createDefault();
        lock.target = "SELF";
        lock.moveTag = "CURSED_ENERGY";
        lock.durationRounds = 1;
        AbilityData data = ability("ACTIVE", "Seal after hit", "SEAL_AFTER_HIT");
        data.activationCondition = hit;
        data.effects = List.of(lock);

        Move attack = attack("ATTACK");
        BattleCombatant owner = combatant("OWNER", List.of(attack), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        AbilityActivationEngine engine = new AbilityActivationEngine(new SeededRandomSource(new FixedRandom()));
        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_HIT, owner, enemy, attack, 1));
        assertTrue(owner.getAbilityFlags().lockedMoveTags.contains("CURSED_ENERGY"));

        CombatResolver resolver = new CombatResolver(new FixedRandom());
        state.transitionTo(BattleState.Phase.ROUND_END);
        resolver.processRoundEnd(state);
        assertTrue(owner.getAbilityFlags().lockedMoveTags.contains("CURSED_ENERGY"));

        state.transitionTo(BattleState.Phase.ROUND_END);
        resolver.processRoundEnd(state);
        assertFalse(owner.getAbilityFlags().lockedMoveTags.contains("CURSED_ENERGY"));
    }

    @Test
    void automaticStatusApplicationsCanActivateStatusPredicates() {
        AbilityEffectData automatic = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        automatic.stringValue = StatusEffectType.ACCURACY_INCREASE.name();
        automatic.target = "SELF";
        automatic.timing = "FIGHT_START";
        automatic.durationRounds = -1;
        automatic.magnitude = 10.0;
        AbilityData source = ability("PASSIVE", "Automatic focus", "AUTO_FOCUS");
        source.effects = List.of(automatic);

        AbilityConditionData receivesFocus = AbilityConditionType.STATUS_APPLIED.createDefault();
        receivesFocus.statusType = StatusEffectType.ACCURACY_INCREASE.name();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData reaction = ability("ACTIVE", "Focus reaction", "FOCUS_REACTION");
        reaction.activationCondition = receivesFocus;
        reaction.effects = List.of(heal);

        BattleCombatant owner = combatant(
            "OWNER", List.of(), List.of(new Ability(source), new Ability(reaction)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(20);
        int before = owner.getCurrentHp();
        BattleState state = new BattleState(owner, enemy);

        new CombatResolver(new FixedRandom()).processRoundStart(state);

        assertEquals(before + 10, owner.getCurrentHp());
    }

    @Test
    void deferredPoolClampEmitsAPlaybackReconciliationEvent() {
        AbilityEffectData automatic = AbilityEffectType.AUTO_STATUS_APPLY.createDefault();
        automatic.stringValue = StatusEffectType.MAX_HP_DECREASE.name();
        automatic.target = AbilityEffectTarget.SELF.name();
        automatic.timing = AbilityEffectTiming.ROUND_START.name();
        automatic.durationRounds = 1;
        automatic.magnitude = 50.0;
        AbilityData source = ability("PASSIVE", "Automatic max HP decrease", "AUTO_MAX_HP_DOWN");
        source.effects = List.of(automatic);

        AbilityConditionData removed = AbilityConditionType.STATUS_REMOVED.createDefault();
        removed.statusType = StatusEffectType.MAX_HP_DECREASE.name();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 200;
        AbilityData reaction = ability("ACTIVE", "Heal on max HP expiry", "HEAL_ON_MAX_EXPIRY");
        reaction.activationCondition = removed;
        reaction.effects = List.of(heal);

        BattleCombatant owner = combatant(
            "OWNER", List.of(), List.of(new Ability(source), new Ability(reaction)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        CombatResolver resolver = new CombatResolver(new FixedRandom());
        resolver.processRoundStart(state);
        owner.applyDamage(100);
        state.transitionTo(BattleState.Phase.ROUND_END);

        List<CombatEvent> events = resolver.processRoundEnd(state);

        assertEquals(owner.getMaxHp(), owner.getCurrentHp());
        int restoredIndex = eventIndex(events, CombatEvent.Type.HP_RESTORED);
        int maximumIndex = eventIndex(events, CombatEvent.Type.MAX_HP_CHANGED);
        assertTrue(restoredIndex >= 0 && maximumIndex > restoredIndex);
        assertEquals(owner.getMaxHp(), events.get(maximumIndex).getIntValue());
    }

    @Test
    void guaranteedAbilityActivationDoesNotShiftCombatRandomness() {
        AbilityEffectData shield = AbilityEffectType.DAMAGE_SHIELD.createDefault();
        AbilityData data = ability("ACTIVE", "Guaranteed", "GUARANTEED");
        data.activationCondition = AbilityConditionData.always();
        data.effects = List.of(shield);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        CountingRandom random = new CountingRandom();

        new CombatResolver(random).processRoundStart(new BattleState(owner, enemy));

        assertEquals(0, random.doubleCalls);
    }

    @Test
    void lethalBlackFlashCompletesBeforeBattleOver() {
        AbilityConditionData blackFlash = AbilityConditionType.BLACK_FLASH_HIT.createDefault();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData reaction = ability("ACTIVE", "Black Flash heal", "BF_HEAL");
        reaction.activationCondition = blackFlash;
        reaction.effects = List.of(heal);

        Move finisher = new Move.Builder("FINISHER")
            .name("Finisher")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(10_000)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
        BattleCombatant attacker = combatant(
            "ATTACKER", List.of(finisher), List.of(new Ability(reaction)));
        BattleCombatant defender = combatant("DEFENDER", List.of(), List.of());
        attacker.applyDamage(20);
        int before = attacker.getCurrentHp();
        Timeline timeline = new Timeline(1);
        timeline.placeAt(finisher, 1, 0);
        attacker.setTimeline(timeline);
        defender.setTimeline(new Timeline(1));
        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom()).resolveRound(state);

        int blackFlashIndex = eventIndex(events, CombatEvent.Type.BLACK_FLASH);
        int battleOverIndex = eventIndex(events, CombatEvent.Type.BATTLE_OVER);
        assertTrue(blackFlashIndex >= 0 && battleOverIndex > blackFlashIndex);
        assertEquals(before + 10, attacker.getCurrentHp());
        assertTrue(attacker.isInBlackFlashState());
    }

    @Test
    void planningStatStatusesReachNextRound() {
        BattleCombatant target = combatant("TARGET", List.of(), List.of());
        target.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.MAX_AP_DECREASE, 1, 10.0), BattleState.Phase.RESOLUTION);
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(target, enemy);
        CombatResolver resolver = new CombatResolver(new FixedRandom());
        state.transitionTo(BattleState.Phase.ROUND_END);
        resolver.processRoundEnd(state);
        assertTrue(target.hasEffect(StatusEffectType.MAX_AP_DECREASE));
    }

    @Test
    void roundEndPredicatesSeeStatusesBeforeTheyExpire() {
        AbilityConditionData roundEnd = AbilityConditionType.PHASE_REACHED.createDefault();
        roundEnd.phase = BattleState.Phase.ROUND_END.name();
        AbilityConditionData focused = AbilityConditionType.HAS_STATUS.createDefault();
        focused.statusType = StatusEffectType.ACCURACY_INCREASE.name();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData data = ability("ACTIVE", "Round end focus", "ROUND_END_FOCUS");
        data.activationCondition = AbilityConditionData.all(List.of(roundEnd, focused));
        data.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(20);
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.ACCURACY_INCREASE, 1, 10.0));
        int before = owner.getCurrentHp();
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.ROUND_END);

        new CombatResolver(new FixedRandom()).processRoundEnd(state);

        assertEquals(before + 10, owner.getCurrentHp());
        assertFalse(owner.hasEffect(StatusEffectType.ACCURACY_INCREASE));
    }

    @Test
    void statusGrantedByExpiryStartsWithItsConfiguredDuration() {
        AbilityConditionData focusRemoved = AbilityConditionType.STATUS_REMOVED.createDefault();
        focusRemoved.statusType = StatusEffectType.ACCURACY_INCREASE.name();
        AbilityEffectData applyEvasionDecrease = AbilityEffectType.APPLY_STATUS.createDefault();
        applyEvasionDecrease.target = "SELF";
        applyEvasionDecrease.stringValue = StatusEffectType.EVASION_DECREASE.name();
        applyEvasionDecrease.durationRounds = 1;
        AbilityData data = ability("ACTIVE", "Expiry reaction", "EXPIRY_REACTION");
        data.activationCondition = focusRemoved;
        data.effects = List.of(applyEvasionDecrease);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.ACCURACY_INCREASE, 1, 10.0));
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.ROUND_END);

        new CombatResolver(new FixedRandom()).processRoundEnd(state);

        com.jjktbf.model.move.StatusEffect evasionDecrease = owner.getActiveEffects().stream()
            .filter(effect -> effect.getType() == StatusEffectType.EVASION_DECREASE)
            .findFirst().orElseThrow();
        assertEquals(1, evasionDecrease.getDurationRounds());
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

    private static Move attack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
    }

    private static BattleCombatant combatant(
        String id,
        List<Move> moves,
        List<Ability> abilities
    ) {
        Character character = new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), null, moves, abilities);
        return new BattleCombatant(character);
    }

    private static int eventIndex(List<CombatEvent> events, CombatEvent.Type type) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).getType() == type) return index;
        }
        return -1;
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

    private static final class CountingRandom extends Random {
        private int doubleCalls;
        @Override public double nextDouble() {
            doubleCalls++;
            return 0.5;
        }
    }
}
