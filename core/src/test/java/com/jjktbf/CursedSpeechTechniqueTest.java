package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.AoeType;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursedSpeechTechniqueTest {

    @Test
    void unifiedCommandChanceGatesPreHitResistanceAndComposedOutcome() {
        Move command = unifiedCommand(0.0);
        BattleCombatant inumaki = cursedSpeechUser("INUMAKI", command);
        BattleCombatant target = fighter("TARGET");
        BattleState state = new BattleState(inumaki, target);
        place(inumaki, command, List.of(target));

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SequenceRandom(0.0)).resolveRound(state);

        assertFalse(target.hasEffect(StatusEffectType.STAGGER));
        assertFalse(events.stream().anyMatch(event -> event.getMessage().contains("takes hold")));
        assertFalse(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_APPLIED && event.getTarget() == target));
    }

    @Test
    void resistanceUsesPostCostCeAndInflictsUncappedScaledRecoil() {
        Move command = command("POST_COST", CursedSpeechAbility.DONT_MOVE, 50, 10, 0, 636);
        BattleCombatant inumaki = cursedSpeechUser("INUMAKI", command);
        BattleCombatant target = fighter("TARGET");
        BattleState state = new BattleState(inumaki, target);
        ActionSegment segment = place(inumaki, command, List.of(target));

        int hpBefore = inumaki.getCurrentHp();
        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SequenceRandom(0.05)).resolveRound(state);

        assertEquals(4, inumaki.getCurrentCe(), "the command cost is paid before its CE check");
        assertEquals(hpBefore - 100, inumaki.getCurrentHp(),
            "the target's 40 reinforcement cap against 4 user CE produces 10x recoil");
        assertFalse(target.hasEffect(StatusEffectType.STAGGER));
        assertTrue(events.stream().anyMatch(event -> event.getMessage().contains("resists")));
        assertTrue(segment.hasFired());
    }

    @Test
    void cursedEnergyOutputCapsChanceAndRecoilCe() {
        Move command = command("OUTPUT_CAP", CursedSpeechAbility.DONT_MOVE, 50, 10, 0, 0);
        Character userDefinition = new SorcererCharacter(
            "LOW_OUTPUT", "LOW_OUTPUT", stats(40), "Cursed Speech", List.of(command));
        BattleCombatant user = new BattleCombatant(userDefinition);
        BattleCombatant target = fighter("TARGET");
        BattleState state = new BattleState(user, target);
        place(user, command, List.of(target));

        int hpBefore = user.getCurrentHp();
        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(
            new SequenceRandom(0.25)).resolveRound(state);

        assertEquals(hpBefore - 20, user.getCurrentHp(),
            "target's 40 reinforcement cap against the user's 20 cap doubles recoil");
        assertFalse(target.hasEffect(StatusEffectType.STAGGER));
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
                && event.getMove() == command
                && event.getIntValue() == 25));
    }

    @Test
    void eachSelectedTargetRollsIndependentlyAndContributesRecoil() {
        Move command = command("GROUP", CursedSpeechAbility.SLEEP, 50, 10, 0, 0);
        BattleCombatant inumaki = cursedSpeechUser("INUMAKI", command);
        BattleCombatant first = fighter("FIRST");
        BattleCombatant second = fighter("SECOND");
        BattleCombatant unselected = fighter("UNSELECTED");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(com.jjktbf.model.combat.BattleTeamId.PLAYER, List.of(inumaki)),
            BattleState.teamOfFighters(com.jjktbf.model.combat.BattleTeamId.ENEMY,
                List.of(first, second, unselected)));
        place(inumaki, command, List.of(first, second));

        int hpBefore = inumaki.getCurrentHp();
        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(
            new SequenceRandom(0.10, 0.50, 0.90)).resolveRound(state);

        assertTrue(first.hasEffect(StatusEffectType.SLEEP));
        assertFalse(second.hasEffect(StatusEffectType.SLEEP));
        assertFalse(unselected.hasEffect(StatusEffectType.SLEEP));
        assertEquals(hpBefore - 20, inumaki.getCurrentHp());
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.DAMAGE_DEALT)
            .filter(event -> event.getSource() == inumaki && event.getTarget() == inumaki)
            .count(), "all selected targets produce one summed recoil hit");
    }

    @Test
    void successfulCommandsBypassBlockAndSleepStopsActionsUntilRoundEnd() {
        Move sleep = command("SLEEP", CursedSpeechAbility.SLEEP, 95, 0, 0, 0);
        BattleCombatant inumaki = cursedSpeechUser("INUMAKI", sleep);
        BattleCombatant target = fighter("TARGET");
        BattleState state = new BattleState(inumaki, target);

        Move block = fullBlock();
        Timeline targetTimeline = new Timeline(60);
        ActionSegment blockSegment = targetTimeline.placeAt(block, 1, 0);
        ActionSegment laterSegment = targetTimeline.placeAt(physicalAttack("LATER", 10), 20, 0);
        assertNotNull(blockSegment);
        assertNotNull(laterSegment);
        target.setTimeline(targetTimeline);
        place(inumaki, sleep, List.of(target), 2);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new SequenceRandom(0.0, 0.5));
        List<CombatEvent> sleepEvents = resolver.resolveRound(state);
        assertTrue(target.hasEffect(StatusEffectType.SLEEP));
        assertTrue(blockSegment.hasFired(), "the block is active before Sleep lands");
        assertFalse(blockSegment.isStunned(), "Sleep cannot undo an action that already fired");
        assertTrue(laterSegment.isStunned(), "Sleep stops later actions at their fire tick");
        assertTrue(sleepEvents.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_STUNNED
                && event.getTarget() == target
                && event.getMove() == laterSegment.getMove()
                && event.getTick() == laterSegment.getFireTick()
                && event.getMessage().contains("TARGET tried to use LATER but was asleep")));
        assertTrue(sleepEvents.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_APPLIED
                && event.getSource() == inumaki
                && event.getTarget() == target
                && event.getMessage().contains("INUMAKI applies Sleep to TARGET")));
        assertNotNull(MoveAvailability.restrictionReason(state, target, physicalAttack("TRY", 1)));

        state.transitionTo(BattleState.Phase.ROUND_END);
        resolver.processRoundEnd(state);
        state.transitionTo(BattleState.Phase.PLANNING);
        assertFalse(target.hasEffect(StatusEffectType.SLEEP),
            "Sleep expires before the next planning phase");
        assertNull(MoveAvailability.restrictionReason(
            state, target, physicalAttack("TRY_AGAIN", 1)));
    }

    @Test
    void damageWakesSleepingTargetBeforeItsLaterAction() {
        Move sleep = command("SLEEP", CursedSpeechAbility.SLEEP, 95, 0, 0, 0);
        Move wake = physicalAttack("WAKE", 30);
        Move later = physicalAttack("LATER", 10);
        BattleCombatant inumaki = cursedSpeechUser("INUMAKI", sleep);
        BattleCombatant target = fighter("TARGET");
        BattleState state = new BattleState(inumaki, target);

        Timeline inumakiTimeline = new Timeline(60);
        ActionSegment sleepSegment = inumakiTimeline.placeAt(sleep, 2, 0);
        ActionSegment wakeSegment = inumakiTimeline.placeAt(wake, 10, 0);
        assertNotNull(sleepSegment);
        assertNotNull(wakeSegment);
        sleepSegment.setTargets(List.of(target.getInstanceId()));
        wakeSegment.setTarget(target.getInstanceId());
        inumaki.setTimeline(inumakiTimeline);

        Timeline targetTimeline = new Timeline(60);
        ActionSegment laterSegment = targetTimeline.placeAt(later, 20, 0);
        assertNotNull(laterSegment);
        laterSegment.setTarget(inumaki.getInstanceId());
        target.setTimeline(targetTimeline);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(
            new SequenceRandom(0.0, 0.5, 0.5)).resolveRound(state);

        assertFalse(target.hasEffect(StatusEffectType.SLEEP));
        assertTrue(laterSegment.hasFired(), "waking before fire allows the action to occur");
        assertFalse(laterSegment.isStunned());
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.STATUS_EXPIRED
                && event.getTarget() == target
                && event.getMessage().contains("TARGET wakes from Sleep")));
        assertFalse(events.stream().anyMatch(event ->
            event.getMessage().contains("TARGET tried to use LATER but was asleep")));
    }

    @Test
    void repeatedMoveConversionPreservesCommandAndRecoilData() {
        MoveData data = commandData(CursedSpeechAbility.SLEEP, 75, 6);

        Move validationMove = data.toMove();
        Move battleMove = data.toMove();

        assertEquals(CursedSpeechAbility.SLEEP,
            CursedSpeechAbility.commandMode(validationMove));
        assertEquals(CursedSpeechAbility.SLEEP,
            CursedSpeechAbility.commandMode(battleMove));
        assertEquals(1, data.onHitEffects.size(), "conversion must not consume repository data");

        Move roundTripped = MoveData.fromMove(battleMove).toMove();
        assertEquals(CursedSpeechAbility.SLEEP,
            CursedSpeechAbility.commandMode(roundTripped));
        StatusEffect effect = roundTripped.getHitComponents().get(0).getOnHitEffects().get(0);
        assertEquals(6, effect.getCodedParameters().get(CursedSpeechAbility.BASE_RECOIL));
    }

    @Test
    void ceAdjustmentMultipliesBaseChanceAndConfiguredBonus() {
        Move command = command("REFINED", CursedSpeechAbility.SLEEP, 40, 0, 0, 0);
        BattleCombatant inumaki = cursedSpeechUser(
            "INUMAKI", command, List.of(refinedCommandsAbility(10)));
        BattleCombatant target = fighter("TARGET");
        BattleState state = new BattleState(inumaki, target);
        place(inumaki, command, List.of(target));

        state.transitionTo(BattleState.Phase.RESOLUTION);
        inumaki.drainCe(620);
        List<CombatEvent> events = new CombatResolver(
            new SequenceRandom(0.24, 0.5)).resolveRound(state);

        assertTrue(target.hasEffect(StatusEffectType.SLEEP),
            "20 user CE against the target's 40 reinforcement cap gives a 0.5 adjustment");
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
                && event.getMove() == command
                && event.getIntValue() == 25));
    }

    @Test
    void finalCommandChanceIsClampedFromOneToNinetyNinePercent() {
        Move lowChanceCommand = command(
            "LOW_CHANCE", CursedSpeechAbility.SLEEP, 5, 0, 0, 636);
        BattleCombatant lowChanceUser = cursedSpeechUser("LOW_USER", lowChanceCommand);
        BattleCombatant strongTarget = fighter("STRONG_TARGET");
        BattleState lowState = new BattleState(lowChanceUser, strongTarget);
        place(lowChanceUser, lowChanceCommand, List.of(strongTarget));
        lowState.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> lowEvents = new CombatResolver(
            new SequenceRandom(0.005, 0.5)).resolveRound(lowState);

        assertTrue(strongTarget.hasEffect(StatusEffectType.SLEEP));
        assertTrue(lowEvents.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
                && event.getMove() == lowChanceCommand
                && event.getIntValue() == 1));

        Move highChanceCommand = command(
            "HIGH_CHANCE", CursedSpeechAbility.SLEEP, 20, 0, 0, 0);
        BattleCombatant highChanceUser = cursedSpeechUser("HIGH_USER", highChanceCommand);
        BattleCombatant weakTarget = fighter("WEAK_TARGET");
        weakTarget.drainCe(weakTarget.getCurrentCe());
        BattleState highState = new BattleState(highChanceUser, weakTarget);
        place(highChanceUser, highChanceCommand, List.of(weakTarget));
        highState.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> highEvents = new CombatResolver(
            new SequenceRandom(0.99, 0.5)).resolveRound(highState);

        assertFalse(weakTarget.hasEffect(StatusEffectType.SLEEP));
        assertTrue(highEvents.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
                && event.getMove() == highChanceCommand
                && event.getIntValue() == 99));
    }

    @Test
    void returnDesummonsAndDieHonorsFatalProtection() {
        Move returnCommand = command("RETURN", CursedSpeechAbility.RETURN, 95, 0, 0, 0);
        BattleCombatant inumaki = cursedSpeechUser("INUMAKI", returnCommand);
        BattleCombatant summoner = fighter("SUMMONER");
        BattleState returnState = new BattleState(inumaki, summoner);
        Character summonDefinition = new ShikigamiCharacter(
            "SUMMON", "Summon", stats(), null, List.of());
        assertTrue(returnState.enqueueSummon(summoner, "SUMMON"));
        BattleCombatant summon = returnState.drainPendingSummons(
            id -> Optional.of(summonDefinition)).get(0);
        place(inumaki, returnCommand, List.of(summon));
        returnState.transitionTo(BattleState.Phase.RESOLUTION);
        new CombatResolver(new SequenceRandom(0.0, 0.5)).resolveRound(returnState);
        assertTrue(summon.isRemoved());

        Move die = command("DIE", CursedSpeechAbility.DIE, 95, 0, 0, 0);
        BattleCombatant executioner = cursedSpeechUser("EXECUTIONER", die);
        BattleCombatant protectedTarget = miracleTarget("PROTECTED");
        BattleState dieState = new BattleState(executioner, protectedTarget);
        CombatResolver resolver = new CombatResolver(new SequenceRandom(0.0, 0.5));
        resolver.processRoundStart(dieState);
        int hpBefore = protectedTarget.getCurrentHp();
        place(executioner, die, List.of(protectedTarget));
        dieState.transitionTo(BattleState.Phase.RESOLUTION);
        resolver.resolveRound(dieState);

        assertEquals(hpBefore, protectedTarget.getCurrentHp());
        assertEquals(5, protectedTarget.getCodedAbilities().state(MiraclesAbility.KEY)
            .orElseThrow().currentValue());

        BattleCombatant genericSurvivor = fighter("GENERIC_SURVIVOR");
        genericSurvivor.addRuntimeAbilityEffect(
            AbilityEffectType.SURVIVE_FATAL_DAMAGE.createDefault());
        BattleCombatant secondExecutioner = cursedSpeechUser("SECOND_EXECUTIONER", die);
        BattleState survivorState = new BattleState(secondExecutioner, genericSurvivor);
        place(secondExecutioner, die, List.of(genericSurvivor));
        survivorState.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> survivorEvents = new CombatResolver(
            new SequenceRandom(0.0, 0.5)).resolveRound(survivorState);

        assertEquals(1, genericSurvivor.getCurrentHp());
        assertTrue(survivorEvents.stream().anyMatch(event ->
            event.getTarget() == genericSurvivor
                && event.getMove() == die
                && event.getMessage().contains("survives the command to die")));
        assertFalse(survivorEvents.stream().anyMatch(event ->
            event.getTarget() == genericSurvivor
                && event.getMessage().contains("struck down")));
    }

    private static ActionSegment place(
        BattleCombatant actor,
        Move move,
        List<BattleCombatant> targets
    ) {
        return place(actor, move, targets, 1);
    }

    private static ActionSegment place(
        BattleCombatant actor,
        Move move,
        List<BattleCombatant> targets,
        int startTick
    ) {
        BattlePlan plan = new BattlePlan(actor.getMaxApBar(), actor.getCurrentCe());
        ActionSegment segment = plan.place(
            move, startTick, actor.computeMoveCeCost(move));
        assertNotNull(segment);
        segment.setTargets(targets.stream().map(BattleCombatant::getInstanceId).toList());
        actor.setTimeline(plan.toLegacyTimeline());
        return actor.getTimeline().getSegments().get(0);
    }

    private static Move command(
        String id,
        String mode,
        int chance,
        int recoil,
        int power,
        int ceCost
    ) {
        StatusEffect command = StatusEffect.coded(
            CursedSpeechAbility.KEY,
            CursedSpeechAbility.COMMAND,
            mode,
            null,
            Map.of(
                CursedSpeechAbility.BASE_CHANCE_PERCENT, chance,
                CursedSpeechAbility.BASE_RECOIL, recoil),
            null);
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.INNATE_TECHNIQUE)
            .tags(Set.of(MoveTag.INNATE_TECHNIQUE, MoveTag.CURSED_ENERGY,
                MoveTag.ATTACK, MoveTag.RANGED, MoveTag.AOE))
            .basePower(power)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .baseCeCost(ceCost)
            .hasCeCost(ceCost > 0)
            .minCeCost(ceCost)
            .maxCeCost(ceCost)
            .aoeType(AoeType.MULTIPLE)
            .aoeTargetCount(3)
            .requiredTechniqueId("Cursed Speech")
            .prerequisites(Map.of("cursedTechniqueMastery", 0))
            .onHitEffects(List.of(command))
            .build();
    }

    private static Move unifiedCommand(double activationChance) {
        MoveEffectData command = AbilityEffectType.CODED_MOVE_ACTION.createDefaultMoveEffect();
        command.effectId = "effect-000000";
        command.trigger = MoveEffectTrigger.ON_HIT.name();
        command.target = AbilityEffectTarget.ENEMY.name();
        command.codedAbilityKey = CursedSpeechAbility.KEY;
        command.codedAction = CursedSpeechAbility.COMMAND;
        command.codedTarget = CursedSpeechAbility.DONT_MOVE;
        command.codedParameters = Map.of(
            CursedSpeechAbility.BASE_CHANCE_PERCENT, 95,
            CursedSpeechAbility.BASE_RECOIL, 0);
        command.activationChanceEnabled = true;
        command.activationChance = activationChance;

        MoveEffectData outcome = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        outcome.effectId = "effect-000001";
        outcome.trigger = MoveEffectTrigger.ON_HIT.name();
        outcome.target = AbilityEffectTarget.ENEMY.name();
        outcome.stringValue = StatusEffectType.STAGGER.name();
        outcome.durationRounds = 0;
        outcome.durationTicks = 6;
        outcome.magnitude = 0.0;
        return new Move.Builder("UNIFIED_COMMAND")
            .name("Unified Command")
            .category(MoveCategory.INNATE_TECHNIQUE)
            .tags(Set.of(MoveTag.INNATE_TECHNIQUE, MoveTag.CURSED_ENERGY,
                MoveTag.ATTACK, MoveTag.RANGED, MoveTag.AOE))
            .basePower(0)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .aoeType(AoeType.MULTIPLE)
            .aoeTargetCount(3)
            .requiredTechniqueId("Cursed Speech")
            .prerequisites(Map.of("cursedTechniqueMastery", 0))
            .effects(List.of(command, outcome))
            .build();
    }

    private static MoveData commandData(String mode, int chance, int recoil) {
        MoveData data = new MoveData();
        data.id = "COMMAND_DATA";
        data.name = "Command Data";
        data.tags = List.of("INNATE_TECHNIQUE", "CURSED_ENERGY", "ATTACK", "RANGED", "AOE");
        data.basePower = 0;
        data.neverMiss = true;
        data.apCost = 1;
        data.unleashPoint = 1;
        data.aoeType = AoeType.MULTIPLE.name();
        data.aoeTargetCount = 3;
        data.requiredTechniqueId = "Cursed Speech";
        data.prerequisites = Map.of("cursedTechniqueMastery", 0);
        MoveData.StatusEffectData command = new MoveData.StatusEffectData();
        command.codedAbilityKey = CursedSpeechAbility.KEY;
        command.codedAction = CursedSpeechAbility.COMMAND;
        command.codedTarget = mode;
        command.codedParameters = Map.of(
            CursedSpeechAbility.BASE_CHANCE_PERCENT, chance,
            CursedSpeechAbility.BASE_RECOIL, recoil);
        data.onHitEffects = List.of(command);
        return data;
    }

    private static Move physicalAttack(String id, int power) {
        return new Move.Builder(id).name(id).category(MoveCategory.PHYSICAL)
            .basePower(power).neverMiss(true).apCost(1).unleashPoint(1).build();
    }

    private static Move fullBlock() {
        return new Move.Builder("BLOCK").name("Block").category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK).blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(100).apCost(10).unleashPoint(1).build();
    }

    private static BattleCombatant cursedSpeechUser(String id, Move move) {
        return cursedSpeechUser(id, move, List.of());
    }

    private static BattleCombatant cursedSpeechUser(
        String id,
        Move move,
        List<Ability> abilities
    ) {
        Character character = new SorcererCharacter(
            id, id, stats(), "Cursed Speech", List.of(move), abilities);
        return new BattleCombatant(character);
    }

    private static Ability refinedCommandsAbility(int bonus) {
        AbilityData ability = new AbilityData();
        ability.id = "REFINED_COMMANDS";
        ability.name = "Refined Commands";
        ability.category = "PASSIVE";
        ability.sourceType = "TECHNIQUE";
        ability.sourceValue = "Cursed Speech";
        AbilityEffectData effect = AbilityEffectType.CODED.createDefault();
        effect.effectId = "effect-000000";
        effect.codedAbilityKey = CursedSpeechAbility.KEY;
        effect.codedFeature = CursedSpeechAbility.REFINED_COMMANDS;
        effect.codedParameters = Map.of(CursedSpeechAbility.SUCCESS_BONUS_PERCENT, bonus);
        ability.effects = List.of(effect);
        return new Ability(ability);
    }

    private static BattleCombatant fighter(String id) {
        return new BattleCombatant(new SorcererCharacter(id, id, stats(), null, List.of()));
    }

    private static BattleCombatant miracleTarget(String id) {
        Character character = new SorcererCharacter(
            id, id, stats(), "Miracles", List.of(), miracleAbilities());
        return new BattleCombatant(character);
    }

    private static CharacterStats stats() {
        return stats(80);
    }

    private static CharacterStats stats(int cursedEnergyOutput) {
        return new CharacterStats.Builder()
            .vitality(80).speed(80).combatAbility(80)
            .cursedEnergyReserves(80).cursedEnergyEfficiency(80)
            .cursedEnergyOutput(cursedEnergyOutput)
            .jujutsuSkill(80).cursedTechniqueMastery(120)
            .build();
    }

    private static List<Ability> miracleAbilities() {
        AbilityData reservoir = codedAbility(
            "RESERVOIR", "PASSIVE", MiraclesAbility.RESERVOIR, null);
        AbilityConditionData fatal = AbilityConditionType.FATAL_DAMAGE.createDefault();
        AbilityConditionData hasMiracle = AbilityConditionType.CODED_STATE_AT_OR_ABOVE.createDefault();
        hasMiracle.codedAbilityKey = MiraclesAbility.KEY;
        hasMiracle.amount = 1;
        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(
            AbilityConditionData.all(List.of(fatal, hasMiracle)));
        rule.targetEffectIds = List.of("effect-000000");
        rule.matchSameTrigger = true;
        AbilityData reprieve = codedAbility(
            "REPRIEVE", "ACTIVE", MiraclesAbility.FATEFUL_REPRIEVE, rule);
        return List.of(new Ability(reservoir), new Ability(reprieve));
    }

    private static AbilityData codedAbility(
        String id,
        String category,
        String feature,
        AbilityConditionRuleData rule
    ) {
        AbilityData ability = new AbilityData();
        ability.id = id;
        ability.name = id;
        ability.category = category;
        ability.sourceType = "TECHNIQUE";
        ability.sourceValue = "Miracles";
        AbilityEffectData effect = AbilityEffectType.CODED.createDefault();
        effect.effectId = "effect-000000";
        effect.codedAbilityKey = MiraclesAbility.KEY;
        effect.codedFeature = feature;
        ability.effects = List.of(effect);
        ability.activationConditions = rule == null ? null : List.of(rule);
        return ability;
    }

    private static final class SequenceRandom implements RandomSource {
        private final ArrayDeque<Double> values;

        private SequenceRandom(double... values) {
            this.values = new ArrayDeque<>();
            for (double value : values) this.values.add(value);
        }

        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() {
            return values.isEmpty() ? 0.0 : values.removeFirst();
        }
        @Override public boolean nextBoolean() { return false; }
    }
}
