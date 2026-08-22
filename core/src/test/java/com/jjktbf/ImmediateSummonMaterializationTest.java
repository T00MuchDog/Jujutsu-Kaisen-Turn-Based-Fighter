package com.jjktbf;

import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.AoeType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Summons materialize at the broadcast of the summoning action (Pokemon-style),
 * not at the end of the tick batch: the COMBATANT_SUMMONED event follows the
 * MOVE_FIRED broadcast on the same tick, later same-tick moves can target the
 * summon, and a shikigami-locked move placed after the summon fails with the
 * default "tried to use X, but it failed!" message. The summon still receives
 * no timeline until the next planning phase.
 */
class ImmediateSummonMaterializationTest {

    @Test
    void effectRowSummonMaterializesAtBroadcastInOrder() {
        BattleCombatant summoner = fighter("SUMMONER");
        BattleState state = state(summoner);
        Move summonNue = summonRowMove("SUMMON_NUE", "NUE");
        place(state, summoner, summonNue, 1);

        List<CombatEvent> events = resolve(state);

        int fired = eventIndex(events, CombatEvent.Type.MOVE_FIRED, summonNue);
        int summonQueued = eventIndex(events, CombatEvent.Type.MOVE_SUMMON, summonNue);
        int summoned = events.indexOf(events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.COMBATANT_SUMMONED)
            .findFirst().orElseThrow());
        assertTrue(summoned > fired,
            "the join broadcast follows the move broadcast on the same tick");
        assertTrue(summonQueued > fired && summoned > summonQueued,
            "MOVE_SUMMON sits between the fire and join broadcasts");
        BattleCombatant summon = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).findFirst().orElseThrow();
        assertEquals(1, events.get(summoned).getTick());
        assertEquals("NUE", summon.getOriginCharacter().getId());
        assertEquals(summoner.getInstanceId(), summon.getSummonerId());
    }

    @Test
    void legacySummonFieldMaterializesOnTheSummonTick() {
        BattleCombatant summoner = fighter("SUMMONER");
        BattleState state = state(summoner);
        Move summonDog = new Move.Builder("SUMMON_DOG")
            .name("Summon Dog").category(MoveCategory.UTILITY)
            .neverMiss(true).apCost(2).unleashPoint(1)
            .summonCharacterId("DOG")
            .build();
        place(state, summoner, summonDog, 1);

        List<CombatEvent> events = resolve(state);

        CombatEvent join = events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.COMBATANT_SUMMONED)
            .findFirst().orElseThrow();
        assertEquals(1, join.getTick(), "the summon joins on the tick it was summoned");
        assertNotNull(state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).findFirst().orElseThrow());
    }

    @Test
    void laterSameTickAoeAcquiresTheSummon() {
        BattleCombatant summoner = fighter("SUMMONER");
        BattleCombatant enemy = fighter("ENEMY");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
        Move summonDog = new Move.Builder("SUMMON_DOG")
            .name("Summon Dog").category(MoveCategory.UTILITY)
            .neverMiss(true).apCost(2).unleashPoint(1)
            .summonCharacterId("DOG")
            .build();
        // Instant summon sorts first on tick 3; the enemy's non-instant AOE
        // fires later in the same tick's order.
        Move aoe = new Move.Builder("AOE")
            .name("AOE Slam").category(MoveCategory.PHYSICAL)
            .neverMiss(true).apCost(3).unleashPoint(3)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.AOE))
            .aoeType(AoeType.ALL_ENEMIES)
            .hitComponents(List.of(new HitComponent(
                10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        Timeline playerTimeline = new Timeline(20);
        playerTimeline.placeAt(summonDog, 3, 0);
        summoner.setTimeline(playerTimeline);
        Timeline enemyTimeline = new Timeline(20);
        enemyTimeline.placeAt(aoe, 1, 0);
        enemy.setTimeline(enemyTimeline);

        List<CombatEvent> events = resolve(state);

        BattleCombatant summon = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).findFirst().orElseThrow();
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.DAMAGE_DEALT
            && event.getTarget() == summon),
            "an AOE firing later in the summon's tick hits it");
    }

    @Test
    void shikigamiLockedMovePlacedAfterTheSummonFails() {
        BattleCombatant summoner = fighter("SUMMONER");
        BattleState state = state(summoner);
        Move summonNue = summonRowMove("SUMMON_NUE", "NUE");
        Move lockedMove = unavailableWhileSummonActive("NUE_STRIKE", "NUE");
        Timeline timeline = new Timeline(20);
        timeline.placeAt(summonNue, 1, 0);
        timeline.placeAt(lockedMove, 4, 0);
        summoner.setTimeline(timeline);
        int startingCe = summoner.getCurrentCe();

        List<CombatEvent> events = resolve(state);

        CombatEvent blocked = events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_STUNNED
                && event.getMove() == lockedMove)
            .findFirst().orElseThrow();
        assertEquals("SUMMONER tried to use NUE_STRIKE, but it failed!",
            blocked.getMessage());
        assertFalse(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_FIRED
            && event.getMove() == lockedMove));
        assertEquals(startingCe, summoner.getCurrentCe(),
            "a move invalidated before it starts must not spend CE");
    }

    @Test
    void summonedShikigamiWaitsForNextRoundToAct() {
        BattleCombatant summoner = fighter("SUMMONER");
        BattleState state = state(summoner);
        Move summonNue = summonRowMove("SUMMON_NUE", "NUE");
        place(state, summoner, summonNue, 1);

        resolve(state);

        BattleCombatant summon = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).findFirst().orElseThrow();
        assertTrue(state.playerTeam().active().stream()
                .anyMatch(candidate -> candidate == summon),
            "the summon is active on its summon round");
        assertEquals(null, summon.getTimeline(),
            "timelines attach during planning, so the summon cannot act this round");
    }

    // --- fixtures ---------------------------------------------------------------

    private static List<CombatEvent> resolve(BattleState state) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new SeededRandomSource(1L),
            ImmediateSummonMaterializationTest::lookup).resolveRound(state);
    }

    private static BattleState state(BattleCombatant summoner) {
        return new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY,
                List.of(fighter("ENEMY"))));
    }

    private static void place(BattleState state, BattleCombatant combatant, Move move, int tick) {
        Timeline timeline = new Timeline(20);
        timeline.placeAt(move, tick, 0);
        combatant.setTimeline(timeline);
    }

    private static int eventIndex(
        List<CombatEvent> events, CombatEvent.Type type, Move move
    ) {
        return events.indexOf(events.stream()
            .filter(event -> event.getType() == type && event.getMove() == move)
            .findFirst().orElseThrow());
    }

    /** A unified-effects move whose ON_FIRE row summons a shikigami (the authored data shape). */
    private static Move summonRowMove(String id, String definitionId) {
        MoveEffectData effect =
            AbilityEffectType.SUMMON_CHARACTER.createDefaultMoveEffect();
        effect.effectId = "effect-0";
        effect.characterId = definitionId;
        effect.trigger = MoveEffectTrigger.ON_FIRE.name();
        effect.condition = AbilityConditionData.always();
        return new Move.Builder(id).name(id).category(MoveCategory.UTILITY)
            .neverMiss(true).apCost(2).unleashPoint(1)
            .effects(List.of(effect))
            .build();
    }

    private static Move unavailableWhileSummonActive(String id, String definitionId) {
        MoveEffectData effect =
            AbilityEffectType.MOVE_UNAVAILABLE_WHILE_OWNED_SUMMON_ACTIVE.createDefaultMoveEffect();
        effect.effectId = "effect-0";
        effect.characterId = definitionId;
        effect.trigger = MoveEffectTrigger.AVAILABILITY.name();
        effect.condition = AbilityConditionData.always();
        return new Move.Builder(id).name(id).category(MoveCategory.UTILITY)
            .neverMiss(true).apCost(2).unleashPoint(1)
            .effects(List.of(effect))
            .build();
    }

    private static Optional<com.jjktbf.model.character.Character> lookup(String id) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).cursedEnergyReserves(100).speed(100).build();
        return Optional.of(new ShikigamiCharacter(
            id, id, stats, null, List.of(), List.of(), Equipment.NONE, 0.0));
    }

    private static BattleCombatant fighter(String id) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).cursedEnergyReserves(100).speed(100).build();
        return new BattleCombatant(new SorcererCharacter(
            id, id, stats, "Ten Shadows", List.of(), List.of(), Equipment.NONE), List.of());
    }
}
