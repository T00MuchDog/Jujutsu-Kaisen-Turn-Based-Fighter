package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionActor;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectParameter;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.ShikigamiMoveRuntime;
import com.jjktbf.model.character.coded.TenShadowsAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.SummonUpkeepScaler;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenShadowsCoreTest {

    @Test
    void effectMetadataAndShikigamiSourceResolutionAreAuthoritative() {
        AbilityEffectData cap = AbilityEffectType.MAX_ACTIVE_SUMMONS.createDefault();
        assertEquals(1, cap.intValue);
        assertTrue(AbilityEffectType.MAX_ACTIVE_SUMMONS.isPassiveOnly());
        assertEquals(Set.of(AbilityEffectParameter.INTEGER),
            AbilityEffectType.MAX_ACTIVE_SUMMONS.parameters());
        assertTrue(AbilityEffectType.MAX_ACTIVE_SUMMONS.masteryProgressionFields(cap)
            .contains(TechniqueMasteryProgressions.INT_VALUE));

        AbilityEffectData upkeep =
            AbilityEffectType.SUMMON_CE_UPKEEP_PER_ACTIVE_TICK.createDefault();
        assertTrue(upkeep.doubleValue > 0.0);
        assertTrue(AbilityEffectType.SUMMON_CE_UPKEEP_PER_ACTIVE_TICK.isPassiveOnly());
        upkeep.doubleValue = 0.0;
        assertTrue(AbilityEffectType.SUMMON_CE_UPKEEP_PER_ACTIVE_TICK
            .validationError(upkeep).contains("greater than 0"));

        AbilityEffectData desummon =
            AbilityEffectType.DESUMMON_OWNED_SHIKIGAMI.createDefault();
        assertTrue(AbilityEffectType.DESUMMON_OWNED_SHIKIGAMI.requiresActivation());
        assertTrue(AbilityEffectType.DESUMMON_OWNED_SHIKIGAMI.parameters().isEmpty());
        assertNull(AbilityEffectType.DESUMMON_OWNED_SHIKIGAMI.validationError(desummon));

        AbilityData shikigamiOnly = abilityData(
            "SHIKIGAMI_ONLY", "Shikigami only", "PASSIVE", "SHIKIGAMI", List.of());
        CharacterData shikigami = new CharacterData();
        shikigami.type = CharacterType.SHIKIGAMI.name();
        shikigami.abilityIds = List.of(shikigamiOnly.id);
        CharacterData sorcerer = new CharacterData();
        sorcerer.abilityIds = List.of(shikigamiOnly.id);

        assertTrue(AbilityResolver.resolve(shikigami, List.of(shikigamiOnly))
            .containsAbility(shikigamiOnly.id));
        assertFalse(AbilityResolver.resolve(sorcerer, List.of(shikigamiOnly))
            .availableAbilityIds().contains(shikigamiOnly.id));
        assertTrue(CodedAbilityRegistry.supportsEffect(
            ShikigamiMoveRuntime.KEY, ShikigamiMoveRuntime.DESUMMON_SELF,
            null, null));

        BattleCombatant minCombined = fighter(
            "MIN_CAP", List.of(capAbility(3), capAbility(2)));
        assertEquals(2, minCombined.getAbilityFlags().maxActiveSummons);

        AbilityEffectData progressingCap =
            AbilityEffectType.MAX_ACTIVE_SUMMONS.createDefault();
        TechniqueMasteryProgressionData progression = new TechniqueMasteryProgressionData();
        progression.mode = TechniqueMasteryProgressionData.BENCHMARKS;
        progression.benchmarks = List.of(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 1),
            new TechniqueMasteryProgressionData.BenchmarkData(60, 3));
        progressingCap.masteryProgression = Map.of(
            TechniqueMasteryProgressions.INT_VALUE, progression);
        Ability progressed = new Ability(abilityData(
            "PROGRESSING_CAP", "Progressing cap", "PASSIVE", "TECHNIQUE",
            List.of(progressingCap)));
        assertEquals(3, fighter("PROGRESSED", List.of(progressed))
            .getAbilityFlags().maxActiveSummons);
    }

    @Test
    void cappedSummonerCountsPendingAndActiveAndRequiresUniqueDefinitions() {
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(2)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));

        assertTrue(state.enqueueSummon(summoner, "DOG"));
        assertFalse(state.enqueueSummon(summoner, "DOG"),
            "a capped summoner cannot queue the same definition twice");
        assertTrue(state.enqueueSummon(summoner, "NUE"));
        assertFalse(state.enqueueSummon(summoner, "TOAD"),
            "pending summons count against the cap");
        assertEquals(2, state.drainPendingSummons(TenShadowsCoreTest::lookup).size());
        assertFalse(state.enqueueSummon(summoner, "TOAD"),
            "active summons count against the cap");

        BattleCombatant unlimited = fighter("UNLIMITED", List.of());
        BattleState unlimitedState = state(unlimited, fighter("OTHER", List.of()));
        assertTrue(unlimitedState.enqueueSummon(unlimited, "DOG"));
        assertTrue(unlimitedState.enqueueSummon(unlimited, "DOG"));
        assertEquals(2, unlimitedState.drainPendingSummons(TenShadowsCoreTest::lookup).size());
    }

    @Test
    void plannedSummonsReserveSlotsBeforeTheRoundResolves() {
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(1)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        Move summonDog = utility("SUMMON_DOG", 1).summonCharacterId("DOG").build();
        Move summonNue = utility("SUMMON_NUE", 1).summonCharacterId("NUE").build();

        assertNotNull(MoveAvailability.restrictionReason(
            state, summoner, summonNue, List.of(summonDog)));
        assertNotNull(MoveAvailability.plannedSummonRestrictionReason(
            summonNue, List.of(summonDog), 2, 1),
            "online drafts reserve only the slots left after active summons");

        int gridLength = TeamBattlePlan.gridLengthForRound(state);
        BattlePlan actorPlan = new BattlePlan(
            summoner.getMaxApBar(), summoner.getCurrentCe(), gridLength);
        assertNotNull(actorPlan.place(summonDog, 1, 0));
        assertNotNull(actorPlan.place(summonNue, 2, 0));
        TeamBattlePlan teamPlan = new TeamBattlePlan(BattleTeamId.PLAYER, gridLength);
        teamPlan.put(summoner.getInstanceId(), actorPlan);

        String error = teamPlan.validationError(state);
        assertNotNull(error);
        assertTrue(error.contains("Maximum active summons reached"));
    }

    @Test
    void summonAssistedMoveIsBlockedOnlyByItsOwnersActiveShikigami() {
        BattleCombatant summoner = fighter("SUMMONER", List.of());
        BattleCombatant enemy = fighter("ENEMY", List.of());
        BattleState state = state(summoner, enemy);
        Move dogAmbush = unavailableWhileSummonsActive("DOG_AMBUSH", "WHITE_DOG", "BLACK_DOG");

        assertTrue(MoveAvailability.isAvailable(state, summoner, dogAmbush));
        summon(state, enemy, "WHITE_DOG");
        assertTrue(MoveAvailability.isAvailable(state, summoner, dogAmbush),
            "another combatant's summon must not disable the move");

        BattleCombatant blackDog = summon(state, summoner, "BLACK_DOG");
        String restriction = MoveAvailability.restrictionReason(state, summoner, dogAmbush);
        assertNotNull(restriction);
        assertTrue(restriction.contains("active on the field"));

        assertEquals(1, state.voluntarilyDesummon(blackDog));
        state.reconcileDefeats();
        assertTrue(MoveAvailability.isAvailable(state, summoner, dogAmbush));
    }

    @Test
    void summonAssistedMoveIsRevalidatedWhenItFires() {
        BattleCombatant summoner = fighter("SUMMONER", List.of());
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        Move assistedMove = unavailableWhileSummonsActive("ASSISTED_MOVE", "DOG");
        int startingCe = summoner.getCurrentCe();
        Timeline timeline = new Timeline(5);
        timeline.placeAt(assistedMove, 1, 10);
        summoner.setTimeline(timeline);

        assertTrue(MoveAvailability.isAvailable(state, summoner, assistedMove));
        summon(state, summoner, "DOG");
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_STUNNED
                && event.getMove() == assistedMove));
        assertFalse(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_FIRED
                && event.getMove() == assistedMove));
        assertEquals(startingCe, summoner.getCurrentCe(),
            "a move invalidated before it starts must not spend CE");
    }

    @Test
    void destroyedSummonIsBlockedForRestOfBattle() {
        BattleCombatant summoner = fighter("SUMMONER",
            List.of(capAbility(2), tenShadowsTechniqueAbility()));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(state, summoner, "DOG");

        dog.receiveInstantKill();
        state.reconcileDefeats();

        assertTrue(state.isSummonDestroyed(summoner, "DOG"));
        assertEquals(Set.of("DOG"), state.destroyedSummonDefinitionIds(summoner));
        assertFalse(state.enqueueSummon(summoner, "DOG"));
        Move summonDog = utility("SUMMON_DOG", 1).summonCharacterId("DOG").build();
        assertFalse(MoveAvailability.isAvailable(state, summoner, summonDog));
        state.endRound();
        assertFalse(state.enqueueSummon(summoner, "DOG"));
    }

    @Test
    void tenShadowsTechniqueAloneRecordsDestroyedSummon() {
        // The Ten Shadows technique by itself — with no MAX_ACTIVE_SUMMONS cap —
        // must still mark a destroyed shikigami unsummonable for the rest of the
        // battle. Permanence is a property of the technique, not the summon cap.
        BattleCombatant summoner = fighter("SUMMONER",
            List.of(tenShadowsTechniqueAbility()));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(state, summoner, "DOG");

        dog.receiveInstantKill();
        state.reconcileDefeats();

        assertTrue(state.isSummonDestroyed(summoner, "DOG"));
        assertFalse(state.enqueueSummon(summoner, "DOG"),
            "a destroyed shikigami cannot be resummoned");
        Move summonDog = utility("SUMMON_DOG", 1).summonCharacterId("DOG").build();
        assertFalse(MoveAvailability.isAvailable(state, summoner, summonDog),
            "the summon move stays greyed out");
        state.endRound();
        assertFalse(state.enqueueSummon(summoner, "DOG"),
            "the destruction persists across rounds");
    }

    @Test
    void destroyedSummonIsNotLockedWithoutTenShadowsTechnique() {
        // A summoner with a cap but NOT the Ten Shadows technique does not gain
        // the permanent-destruction restriction. Guards against re-coupling
        // permanence to the unrelated maxActiveSummons cap.
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(2)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(state, summoner, "DOG");

        dog.receiveInstantKill();
        state.reconcileDefeats();

        assertFalse(state.isSummonDestroyed(summoner, "DOG"));
        assertTrue(state.enqueueSummon(summoner, "DOG"),
            "without the Ten Shadows technique a destroyed summon is not locked");
    }

    @Test
    void destroyedSummonEmitsDefeatAndDestructionEvents() {
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(1)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(state, summoner, "DOG");
        dog.receiveInstantKill();
        Timeline timeline = new Timeline(5);
        timeline.placeAt(utility("ACT", 1).build(), 1, 0);
        summoner.setTimeline(timeline);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);

        assertEquals(1, events.stream().filter(event ->
            event.getType() == CombatEvent.Type.COMBATANT_DEFEATED
                && event.getTarget() == dog).count());
        assertEquals(1, events.stream().filter(event ->
            event.getType() == CombatEvent.Type.COMBATANT_REMOVED
                && event.getTarget() == dog
                && event.getMessage().contains("destroyed")).count());
    }

    @Test
    void voluntaryDesummonAllowsFullRematerializationNextRound() {
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(1)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(state, summoner, "DOG");
        dog.receiveDamage(25);
        dog.drainCe(12);

        assertEquals(1, state.voluntarilyDesummon(dog));
        assertEquals(List.of(dog), state.reconcileDefeats());
        assertTrue(state.isSummonOnCooldown(summoner, "DOG"));
        assertFalse(state.enqueueSummon(summoner, "DOG"));

        state.endRound();
        assertFalse(state.isSummonOnCooldown(summoner, "DOG"));
        BattleCombatant rematerialized = summon(state, summoner, "DOG");
        assertEquals(rematerialized.getMaxHp(), rematerialized.getCurrentHp());
        assertEquals(rematerialized.getMaxCursedEnergy(), rematerialized.getCurrentCe());
    }

    @Test
    void baseAndModifiedFractionalUpkeepChargeOnlyTicksWithResolvedActivity() {
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(1)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        summon(state, summoner, "DOG", 0.3, List.of(upkeepAbility(0.1)), List.of());
        int startingCe = summoner.getCurrentCe();

        Move occupyTwoTicks = utility("WAIT", 2).build();
        Timeline timeline = new Timeline(10);
        timeline.placeAt(occupyTwoTicks, 1, 0);
        timeline.placeAt(occupyTwoTicks, 5, 0);
        summoner.setTimeline(timeline);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);

        assertEquals(startingCe - 1, summoner.getCurrentCe(),
            "four active ticks accrue 1.6 CE; empty ticks 3 and 4 accrue nothing");
        assertEquals(0.6, summoner.getSummonCeUpkeepDebt(), 0.000001);
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.CE_DRAINED)
            .mapToInt(CombatEvent::getIntValue).sum());
    }

    @Test
    void summonUpkeepScalerMapsEfficiencyToMultiplierAtTheDesignAnchors() {
        // RAW efficiency is scaled internally; baseline 80 is the neutral 1.0× point.
        assertEquals(2.0, SummonUpkeepScaler.upkeepMultiplier(10), 0.000001,
            "raw 10 → scaled 10 → 2.0× upkeep");
        assertEquals(1.0, SummonUpkeepScaler.upkeepMultiplier(80), 0.000001,
            "raw 80 → scaled 80 → 1.0× upkeep (neutral baseline)");
        assertEquals(0.2, SummonUpkeepScaler.upkeepMultiplier(300), 0.000001,
            "raw 300 → scaled 472 → 0.2× upkeep");
        // Low-branch midpoint: scaled 45 → 2.0 - (45 - 10) / 70 = 1.5×.
        assertEquals(1.5, SummonUpkeepScaler.upkeepMultiplier(45), 0.000001,
            "raw 45 → scaled 45 → 1.5× upkeep (low-branch midpoint)");
    }

    @Test
    void inefficientSummonerPaysScaledUpkeepAcrossActiveTicks() {
        // CE Efficiency 10 → scaled 10 → 2.0× upkeep multiplier.
        BattleCombatant summoner = fighter("SUMMONER", 10, List.of(capAbility(1)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        summon(state, summoner, "DOG", 0.3, List.of(upkeepAbility(0.1)), List.of());
        int startingCe = summoner.getCurrentCe();

        Move occupyTwoTicks = utility("WAIT", 2).build();
        Timeline timeline = new Timeline(10);
        timeline.placeAt(occupyTwoTicks, 1, 0);
        timeline.placeAt(occupyTwoTicks, 5, 0);
        summoner.setTimeline(timeline);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);

        // base 0.3 + ability 0.1 = 0.4/tick × 2.0× (efficiency 10) × 4 active ticks
        // = 3.2 → 3 CE drained, 0.2 fractional debt carried forward.
        assertEquals(startingCe - 3, summoner.getCurrentCe(),
            "inefficient summoner accrues upkeep at 2.0× across four active ticks");
        assertEquals(0.2, summoner.getSummonCeUpkeepDebt(), 0.000001);
        assertEquals(3, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.CE_DRAINED)
            .mapToInt(CombatEvent::getIntValue).sum());
    }

    @Test
    void codedDesummonSelfRemovesSummonExactlyOnce() {
        Move desummonMove = utility("DESUMMON", 1)
            .selfEffects(List.of(StatusEffect.coded(
                ShikigamiMoveRuntime.KEY, ShikigamiMoveRuntime.DESUMMON_SELF)))
            .build();
        BattleCombatant summoner = fighter("SUMMONER", List.of(capAbility(1)));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(state, summoner, "DOG", List.of(), List.of(desummonMove));
        Timeline timeline = new Timeline(5);
        timeline.placeAt(desummonMove, 1, 0);
        dog.setTimeline(timeline);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);

        assertTrue(dog.isRemoved());
        assertTrue(state.isSummonOnCooldown(summoner, "DOG"));
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.COMBATANT_REMOVED)
            .filter(event -> event.getTarget() == dog)
            .count());
    }

    @Test
    void zeroCeUpkeepActivatesShadowsUnravelAndDismissesAllOwnedSummons() {
        BattleCombatant summoner = fighter(
            "SUMMONER", List.of(capAbility(2), shadowsUnravelAbility()));
        BattleState state = state(summoner, fighter("ENEMY", List.of()));
        BattleCombatant dog = summon(
            state, summoner, "DOG", List.of(upkeepAbility(0.5)), List.of());
        BattleCombatant nue = summon(
            state, summoner, "NUE", List.of(upkeepAbility(0.5)), List.of());
        summoner.drainCe(summoner.getCurrentCe() - 1);
        Timeline timeline = new Timeline(5);
        timeline.placeAt(utility("ACT", 1).build(), 1, 0);
        summoner.setTimeline(timeline);

        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);

        assertEquals(0, summoner.getCurrentCe());
        assertTrue(dog.isRemoved());
        assertTrue(nue.isRemoved());
        assertTrue(events.stream().anyMatch(
            event -> event.getType() == CombatEvent.Type.CE_DEPLETED));
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.ABILITY_ACTIVATED
                && event.getSource() == summoner));
        assertEquals(2, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.COMBATANT_REMOVED)
            .count());
    }

    @Test
    void summonedShikigamiScalesWithSummonerGoverningStats() {
        // A peak CTM/Output summoner doubles an innate (default) summon's stats.
        CharacterStats peak = new CharacterStats.Builder()
            .cursedTechniqueMastery(300).cursedEnergyOutput(300)
            .vitality(100).cursedEnergyReserves(100).speed(100)
            .build();
        BattleCombatant peakSummoner = new BattleCombatant(new SorcererCharacter(
            "PEAK", "PEAK", peak, "Ten Shadows", List.of(), List.of(), false), List.of());
        BattleState peakState = state(peakSummoner, fighter("ENEMY", List.of()));
        BattleCombatant peakDog = summon(peakState, peakSummoner, "DOG");
        assertEquals(200, peakDog.getEffectiveStats().getVitality(),
            "VIT 100 x2.0 (peak innate summoner) = 200");

        // A baseline (CTM=CEO=80) summoner is the neutral point: no scaling.
        BattleCombatant baselineSummoner = fighter("BASELINE", List.of());
        BattleState baselineState = state(baselineSummoner, fighter("ENEMY", List.of()));
        BattleCombatant baselineDog = summon(baselineState, baselineSummoner, "DOG");
        assertEquals(100, baselineDog.getEffectiveStats().getVitality(),
            "baseline summoner leaves authored VIT at 100");
    }

    private static BattleState state(BattleCombatant player, BattleCombatant enemy) {
        return new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(player)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
    }

    private static BattleCombatant summon(
        BattleState state,
        BattleCombatant summoner,
        String definitionId
    ) {
        return summon(state, summoner, definitionId, List.of(), List.of());
    }

    private static BattleCombatant summon(
        BattleState state,
        BattleCombatant summoner,
        String definitionId,
        List<Ability> abilities,
        List<Move> moves
    ) {
        return summon(state, summoner, definitionId, 0.0, abilities, moves);
    }

    private static BattleCombatant summon(
        BattleState state,
        BattleCombatant summoner,
        String definitionId,
        double baseCeDrainPerTick,
        List<Ability> abilities,
        List<Move> moves
    ) {
        assertTrue(state.enqueueSummon(summoner, definitionId));
        List<BattleCombatant> created = state.drainPendingSummons(id -> Optional.of(
            shikigami(id, abilities, moves, baseCeDrainPerTick)));
        assertEquals(1, created.size());
        return created.get(0);
    }

    private static Optional<com.jjktbf.model.character.Character> lookup(String id) {
        return Optional.of(shikigami(id, List.of(), List.of()));
    }

    private static BattleCombatant fighter(String id, List<Ability> abilities) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).cursedEnergyReserves(100).speed(100).build();
        return new BattleCombatant(new SorcererCharacter(
            id, id, stats, "Ten Shadows", List.of(), abilities, false), abilities);
    }

    private static BattleCombatant fighter(String id, int ceEfficiency, List<Ability> abilities) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).cursedEnergyReserves(100).speed(100)
            .cursedEnergyEfficiency(ceEfficiency).build();
        return new BattleCombatant(new SorcererCharacter(
            id, id, stats, "Ten Shadows", List.of(), abilities, false), abilities);
    }

    private static ShikigamiCharacter shikigami(
        String id,
        List<Ability> abilities,
        List<Move> moves
    ) {
        return shikigami(id, abilities, moves, 0.0);
    }

    private static ShikigamiCharacter shikigami(
        String id,
        List<Ability> abilities,
        List<Move> moves,
        double baseCeDrainPerTick
    ) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).cursedEnergyReserves(100).speed(100).build();
        return new ShikigamiCharacter(
            id, id, stats, null, moves, abilities, false, baseCeDrainPerTick);
    }

    private static Ability capAbility(int cap) {
        AbilityEffectData effect = AbilityEffectType.MAX_ACTIVE_SUMMONS.createDefault();
        effect.intValue = cap;
        return new Ability(abilityData("CAP_" + cap, "Cap", "PASSIVE", "TECHNIQUE",
            List.of(effect)));
    }

    private static Ability tenShadowsTechniqueAbility() {
        AbilityEffectData effect = AbilityEffectType.CODED.createDefault();
        effect.codedAbilityKey = TenShadowsAbility.KEY;
        effect.codedFeature = TenShadowsAbility.TECHNIQUE;
        return new Ability(abilityData(
            "TEN_SHADOWS", "Ten Shadows Technique", "PASSIVE", "TECHNIQUE",
            List.of(effect)));
    }

    private static Ability upkeepAbility(double rate) {
        AbilityEffectData effect =
            AbilityEffectType.SUMMON_CE_UPKEEP_PER_ACTIVE_TICK.createDefault();
        effect.doubleValue = rate;
        return new Ability(abilityData("UPKEEP_" + rate, "Upkeep", "PASSIVE", "SHIKIGAMI",
            List.of(effect)));
    }

    private static Ability shadowsUnravelAbility() {
        AbilityEffectData effect =
            AbilityEffectType.DESUMMON_OWNED_SHIKIGAMI.createDefault();
        effect.effectId = "effect-000000";
        AbilityConditionData condition =
            AbilityConditionType.CE_VALUE_AT_OR_BELOW.createDefault();
        condition.actor = AbilityConditionActor.SELF.name();
        condition.amount = 0;
        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(condition);
        rule.targetEffectIds = List.of(effect.effectId);
        rule.matchSameTrigger = true;
        AbilityData data = abilityData(
            "SHADOWS_UNRAVEL", "Shadows Unravel", "ACTIVE", "TECHNIQUE", List.of(effect));
        data.activationConditions = List.of(rule);
        return new Ability(data);
    }

    private static AbilityData abilityData(
        String id,
        String name,
        String category,
        String sourceType,
        List<AbilityEffectData> effects
    ) {
        AbilityData data = new AbilityData();
        data.id = id;
        data.name = name;
        data.category = category;
        data.sourceType = sourceType;
        data.sourceValue = "TECHNIQUE".equals(sourceType) ? "Ten Shadows" : null;
        data.effects = effects;
        return data;
    }

    private static Move.Builder utility(String id, int apCost) {
        return new Move.Builder(id).name(id).category(MoveCategory.UTILITY)
            .neverMiss(true).apCost(apCost).unleashPoint(1);
    }

    private static Move unavailableWhileSummonsActive(String id, String... definitionIds) {
        List<MoveEffectData> constraints = java.util.stream.IntStream
            .range(0, definitionIds.length)
            .mapToObj(index -> {
                MoveEffectData effect = AbilityEffectType
                    .MOVE_UNAVAILABLE_WHILE_OWNED_SUMMON_ACTIVE.createDefaultMoveEffect();
                effect.effectId = "effect-" + index;
                effect.characterId = definitionIds[index];
                effect.trigger = MoveEffectTrigger.AVAILABILITY.name();
                effect.condition = AbilityConditionData.always();
                return effect;
            })
            .toList();
        return utility(id, 1).effects(constraints).build();
    }
}
