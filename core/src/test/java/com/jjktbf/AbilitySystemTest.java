package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityConditionActor;
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
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.PassiveAbilityEngine;
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
            if (ability.isCoded()) {
                assertTrue(CodedAbilityRegistry.supportsAbility(
                    ability.codedAbilityKey, ability.codedFeature), ability.name);
                assertTrue(ability.effects == null || ability.effects.isEmpty(), ability.name);
                continue;
            }
            assertFalse(ability.effects == null || ability.effects.isEmpty(), ability.name);
            for (AbilityEffectData effect : ability.effects) {
                AbilityEffectType type = AbilityEffectType.fromName(effect.type);
                assertNull(type.validationError(effect), ability.name + ": " + type.name());
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
    void intersectedEventConditionsCanCompleteAcrossDistinctBattleEvents() {
        Move attack = attack("ATTACK");
        AbilityConditionData usedMove = AbilityConditionType.MOVE_USED.createDefault();
        usedMove.moveId = attack.getId();
        AbilityConditionData hit = AbilityConditionType.ATTACK_HIT.createDefault();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 20;

        AbilityData data = ability("PASSIVE", "Sequence", "SEQUENCE");
        data.activationCondition = AbilityConditionData.all(List.of(usedMove, hit));
        data.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(attack), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(50);
        int damagedHp = owner.getCurrentHp();
        BattleState state = new BattleState(owner, enemy);
        PassiveAbilityEngine engine = new PassiveAbilityEngine(new SeededRandomSource(new FixedRandom()));

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

        AbilityData threshold = ability("PASSIVE", "Threshold", "THRESHOLD");
        threshold.activationCondition = hp;
        threshold.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(threshold)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        PassiveAbilityEngine engine = new PassiveAbilityEngine(new SeededRandomSource(new FixedRandom()));
        owner.applyDamage(owner.getMaxHp() / 2);
        int before = owner.getCurrentHp();

        engine.process(state, AbilityTrigger.phase(BattleState.Phase.RESOLUTION));
        assertEquals(before + 10, owner.getCurrentHp());
        engine.process(state, AbilityTrigger.tick(1));
        assertEquals(before + 10, owner.getCurrentHp(), "A continuously true threshold must not fire every tick");

        AbilityData impossibleChance = ability("PASSIVE", "No proc", "NO_PROC");
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

        AbilityData data = ability("PASSIVE", "Battle setup", "SETUP");
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
    void statusTriggeredAbilitiesCannotRecursivelyActivateThemselves() {
        AbilityConditionData poisoned = AbilityConditionType.STATUS_APPLIED.createDefault();
        poisoned.statusType = StatusEffectType.POISON.name();
        AbilityEffectData applyPoison = AbilityEffectType.APPLY_STATUS.createDefault();
        applyPoison.stringValue = StatusEffectType.POISON.name();
        applyPoison.target = "SELF";

        AbilityData data = ability("PASSIVE", "Poison loop", "POISON_LOOP");
        data.activationCondition = poisoned;
        data.effects = List.of(applyPoison);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);

        List<CombatEvent> events = new PassiveAbilityEngine(
            new SeededRandomSource(new FixedRandom())).process(
                state,
                AbilityTrigger.status(AbilityTrigger.Type.STATUS_APPLIED,
                    owner, StatusEffectType.POISON, 1));

        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.ABILITY_ACTIVATED)
            .count());
        assertEquals(1, owner.getActiveEffects().size());
    }

    @Test
    void passiveInstantKillEndsTheBattleBeforePlanningContinues() {
        AbilityEffectData kill = AbilityEffectType.INSTANT_KILL.createDefault();
        kill.target = "ENEMY";
        AbilityData data = ability("PASSIVE", "Opening kill", "OPENING_KILL");
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
        AbilityData data = ability("PASSIVE", "Seal after hit", "SEAL_AFTER_HIT");
        data.activationCondition = hit;
        data.effects = List.of(lock);

        Move attack = attack("ATTACK");
        BattleCombatant owner = combatant("OWNER", List.of(attack), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        PassiveAbilityEngine engine = new PassiveAbilityEngine(new SeededRandomSource(new FixedRandom()));
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
        automatic.stringValue = StatusEffectType.FOCUS.name();
        automatic.target = "SELF";
        automatic.timing = "FIGHT_START";
        automatic.durationRounds = -1;
        automatic.magnitude = 0.1;
        AbilityData source = ability("PASSIVE", "Automatic focus", "AUTO_FOCUS");
        source.effects = List.of(automatic);

        AbilityConditionData receivesFocus = AbilityConditionType.STATUS_APPLIED.createDefault();
        receivesFocus.statusType = StatusEffectType.FOCUS.name();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData reaction = ability("PASSIVE", "Focus reaction", "FOCUS_REACTION");
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
    void guaranteedAbilityActivationDoesNotShiftCombatRandomness() {
        AbilityEffectData shield = AbilityEffectType.DAMAGE_SHIELD.createDefault();
        AbilityData data = ability("PASSIVE", "Guaranteed", "GUARANTEED");
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
        AbilityData reaction = ability("PASSIVE", "Black Flash heal", "BF_HEAL");
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
    void barrierConsumesOneStackAndPlanningStatusesReachNextRound() {
        BattleCombatant target = combatant("TARGET", List.of(), List.of());
        target.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.BARRIER, -1, 1.0));
        target.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.BARRIER, -1, 1.0));
        assertEquals(0, target.receiveDamage(20));
        assertEquals(1, target.getActiveEffects().stream()
            .filter(effect -> effect.getType() == StatusEffectType.BARRIER).count());

        target.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.CE_SUPPRESSION, 1, 1.0), BattleState.Phase.RESOLUTION);
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(target, enemy);
        CombatResolver resolver = new CombatResolver(new FixedRandom());
        state.transitionTo(BattleState.Phase.ROUND_END);
        resolver.processRoundEnd(state);
        assertTrue(target.hasEffect(StatusEffectType.CE_SUPPRESSION));
    }

    @Test
    void roundEndPredicatesSeeStatusesBeforeTheyExpire() {
        AbilityConditionData roundEnd = AbilityConditionType.PHASE_REACHED.createDefault();
        roundEnd.phase = BattleState.Phase.ROUND_END.name();
        AbilityConditionData focused = AbilityConditionType.HAS_STATUS.createDefault();
        focused.statusType = StatusEffectType.FOCUS.name();
        AbilityEffectData heal = AbilityEffectType.HEAL_HP.createDefault();
        heal.intValue = 10;
        AbilityData data = ability("PASSIVE", "Round end focus", "ROUND_END_FOCUS");
        data.activationCondition = AbilityConditionData.all(List.of(roundEnd, focused));
        data.effects = List.of(heal);

        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.applyDamage(20);
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.FOCUS, 1, 0.1));
        int before = owner.getCurrentHp();
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.ROUND_END);

        new CombatResolver(new FixedRandom()).processRoundEnd(state);

        assertEquals(before + 10, owner.getCurrentHp());
        assertFalse(owner.hasEffect(StatusEffectType.FOCUS));
    }

    @Test
    void statusGrantedByExpiryStartsWithItsConfiguredDuration() {
        AbilityConditionData focusRemoved = AbilityConditionType.STATUS_REMOVED.createDefault();
        focusRemoved.statusType = StatusEffectType.FOCUS.name();
        AbilityEffectData applyBind = AbilityEffectType.APPLY_STATUS.createDefault();
        applyBind.target = "SELF";
        applyBind.stringValue = StatusEffectType.BIND.name();
        applyBind.durationRounds = 1;
        AbilityData data = ability("PASSIVE", "Expiry reaction", "EXPIRY_REACTION");
        data.activationCondition = focusRemoved;
        data.effects = List.of(applyBind);
        BattleCombatant owner = combatant("OWNER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        owner.addStatusEffect(new com.jjktbf.model.move.StatusEffect(
            StatusEffectType.FOCUS, 1, 0.1));
        BattleState state = new BattleState(owner, enemy);
        state.transitionTo(BattleState.Phase.ROUND_END);

        new CombatResolver(new FixedRandom()).processRoundEnd(state);

        com.jjktbf.model.move.StatusEffect bind = owner.getActiveEffects().stream()
            .filter(effect -> effect.getType() == StatusEffectType.BIND)
            .findFirst().orElseThrow();
        assertEquals(1, bind.getDurationRounds());
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
