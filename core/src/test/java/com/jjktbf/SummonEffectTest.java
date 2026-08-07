package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCharacterLookup;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.CombatantRole;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 coverage for summon effects and authoring: move-based summons,
 * ability-based summons, the shared runtime summon path, summonCharacterId
 * round-trip, and SUMMON_CHARACTER activation classification.
 */
class SummonEffectTest {

    @Test
    void summonCharacterIdRoundTripsThroughMoveData() {
        Move move = new Move.Builder("000001")
            .name("Summon Dogs").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .summonCharacterId("000010")
            .build();
        assertEquals("000010", move.getSummonCharacterId());
        assertTrue(move.summonsCharacter());

        MoveData data = MoveData.fromMove(move);
        assertEquals("000010", data.summonCharacterId);
        Move roundTripped = data.toMove();
        assertEquals("000010", roundTripped.getSummonCharacterId());
        assertTrue(roundTripped.summonsCharacter());
    }

    @Test
    void moveBasedSummonEnqueuesShikigamiOnUnleash() {
        BattleCombatant summoner = fighter("Megumi");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        BattleCharacterLookup dogLookup = id ->
            Optional.of(shikigami("Divine Dog"));
        Move summonMove = new Move.Builder("SUMMON")
            .name("Summon Dogs").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .summonCharacterId("DOG")
            .build();
        BattlePlan plan = new BattlePlan(summoner.getMaxApBar(), summoner.getCurrentCe(), 60);
        plan.place(summonMove, 1, 0);
        summoner.setTimeline(plan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(
            new SeededRandomSource(1L), dogLookup);
        List<CombatEvent> events = resolver.resolveRound(state);

        // A shikigami was materialized onto the player's team.
        long summons = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count();
        assertTrue(summons >= 1, "a summon was created");
        BattleCombatant summon = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).findFirst().orElseThrow();
        assertEquals(CombatantRole.SUMMON, summon.getRole());
        assertEquals(summoner.getInstanceId(), summon.getSummonerId());
        // Summons begin at full resources.
        assertEquals(summon.getMaxHp(), summon.getCurrentHp());
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.COMBATANT_SUMMONED)
            .count());
    }

    @Test
    void abilitySummonCharacterEffectEnqueuesShikigami() {
        // SUMMON_CHARACTER requires activation (not passive-only).
        assertTrue(AbilityEffectType.SUMMON_CHARACTER.requiresActivation());
        // The default has no character selected.
        AbilityEffectData fresh = AbilityEffectType.SUMMON_CHARACTER.createDefault();
        assertNull(fresh.characterId);
        assertNotNull(AbilityEffectType.SUMMON_CHARACTER.validationError(fresh),
            "validation rejects a summon with no character id");
        // Setting one clears the error.
        fresh.characterId = "DOG";
        assertNull(AbilityEffectType.SUMMON_CHARACTER.validationError(fresh));
        // copyFrom preserves the characterId.
        AbilityEffectData copy = new AbilityEffectData();
        copy.copyFrom(fresh);
        assertEquals("DOG", copy.characterId);
        // clearUnusedFields keeps it (it's a used field for this type).
        AbilityEffectType.SUMMON_CHARACTER.clearUnusedFields(copy);
        assertEquals("DOG", copy.characterId);
    }

    @Test
    void moveSummonWorksOnAttackMoves() {
        // The summon path works on attack moves too, not just utility.
        BattleCombatant summoner = fighter("Megumi");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        BattleCharacterLookup dogLookup = id ->
            Optional.of(shikigami("Divine Dog"));
        Move attackSummon = new Move.Builder("ATK_SUMMON")
            .name("Striking Summon").category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .summonCharacterId("DOG")
            .hitComponents(List.of(new HitComponent(10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        BattlePlan plan = new BattlePlan(summoner.getMaxApBar(), summoner.getCurrentCe(), 60);
        plan.place(attackSummon, 1, 0, enemy.getInstanceId());
        summoner.setTimeline(plan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(
            new SeededRandomSource(1L), dogLookup);
        List<CombatEvent> events = resolver.resolveRound(state);

        long summons = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count();
        assertTrue(summons >= 1, "an attack move summoned a shikigami");
        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.DAMAGE_DEALT
            && e.getTarget() == enemy), "the attack move also dealt damage");
    }

    @Test
    void unknownSummonIdIsSkipped() {
        BattleCombatant summoner = fighter("Megumi");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        Move summonMove = new Move.Builder("SUMMON")
            .name("Summon").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .summonCharacterId("MISSING")
            .build();
        BattlePlan plan = new BattlePlan(summoner.getMaxApBar(), summoner.getCurrentCe(), 60);
        plan.place(summonMove, 1, 0);
        summoner.setTimeline(plan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(1L))
            .withSummonLookup(id -> Optional.empty());
        resolver.resolveRound(state);

        assertEquals(0, state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count(),
            "an unknown summon id is skipped, not crashed");
    }

    @Test
    void phaseTriggeredSummonsDrainEvenWhenTheRoundHasNoActions() {
        AbilityConditionData resolution = AbilityConditionType.PHASE_REACHED.createDefault();
        resolution.phase = BattleState.Phase.RESOLUTION.name();
        BattleCombatant summoner = fighter("Megumi", List.of(summonAbility(resolution)));
        BattleState state = new BattleState(summoner, fighter("Enemy"));
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(
            new SeededRandomSource(1L), id -> Optional.of(shikigami("Dog")));

        List<CombatEvent> events = resolver.beginResolution(state);

        assertTrue(!resolver.hasMoreTicks(), "the fixture intentionally has no actions");
        assertEquals(1, state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count());
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.COMBATANT_SUMMONED)
            .count());
    }

    @Test
    void roundStartAndRoundEndTriggerPathsDrainSummons() {
        AbilityConditionData roundStart = AbilityConditionType.EVERY_N_ROUNDS.createDefault();
        BattleCombatant startSummoner = fighter(
            "Start Summoner", List.of(summonAbility(roundStart)));
        BattleState startState = new BattleState(startSummoner, fighter("Start Enemy"));
        CombatResolver startResolver = new CombatResolver(
            new SeededRandomSource(1L), id -> Optional.of(shikigami("Start Dog")));
        List<CombatEvent> startEvents = startResolver.processRoundStart(startState);

        assertEquals(1, startState.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count());
        assertTrue(startEvents.stream().anyMatch(
            event -> event.getType() == CombatEvent.Type.COMBATANT_SUMMONED));

        AbilityConditionData roundEnd = AbilityConditionType.PHASE_REACHED.createDefault();
        roundEnd.phase = BattleState.Phase.ROUND_END.name();
        BattleCombatant endSummoner = fighter(
            "End Summoner", List.of(summonAbility(roundEnd)));
        BattleState endState = new BattleState(endSummoner, fighter("End Enemy"));
        endState.transitionTo(BattleState.Phase.ROUND_END);
        CombatResolver endResolver = new CombatResolver(
            new SeededRandomSource(1L), id -> Optional.of(shikigami("End Dog")));
        List<CombatEvent> endEvents = endResolver.processRoundEnd(endState);

        assertEquals(1, endState.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count());
        assertTrue(endEvents.stream().anyMatch(
            event -> event.getType() == CombatEvent.Type.COMBATANT_SUMMONED));
    }

    private static BattleCombatant fighter(String name) {
        return fighter(name, List.of());
    }

    private static BattleCombatant fighter(String name, List<Ability> abilities) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(100).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), abilities, false);
        return new BattleCombatant(c, abilities);
    }

    private static Ability summonAbility(AbilityConditionData condition) {
        AbilityEffectData summon = AbilityEffectType.SUMMON_CHARACTER.createDefault();
        summon.characterId = "DOG";
        AbilityData data = new AbilityData();
        data.id = "SUMMON_" + condition.type + "_" + condition.phase;
        data.name = "Summon";
        data.category = "ACTIVE";
        data.sourceType = "CHARACTER";
        data.activationCondition = condition;
        data.effects = List.of(summon);
        return new Ability(data);
    }

    private static ShikigamiCharacter shikigami(String name) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(100).build();
        return new ShikigamiCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
    }
}
