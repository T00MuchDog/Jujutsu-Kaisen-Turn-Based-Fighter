package com.jjktbf;

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
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Coverage for the summon-as-a-move-effect flavour: a move's self / on-hit /
 * defense effect row that carries a {@code summonCharacterId}, distinct from the
 * legacy {@code Move.summonCharacterId} unleash field and the ability
 * {@code SUMMON_CHARACTER} effect. All three converge on the shared runtime
 * summon path (enqueue + drain).
 */
class MoveSummonEffectTest {

    @Test
    void summonEffectRowIsSummonFlavouredAndRoundTrips() {
        StatusEffect effect = new StatusEffect("DOG");
        assertTrue(effect.isSummon());
        assertFalse(effect.isCoded());
        assertEquals("DOG", effect.getSummonCharacterId());
        assertEquals("StatusEffect{SUMMON DOG}", effect.toString());
    }

    @Test
    void summonEffectRowCannotAlsoBeCoded() {
        // A DTO carrying both a summon id and a coded key is rejected on load.
        MoveData data = new MoveData();
        data.id = "BAD";
        data.name = "Bad";
        data.apCost = 2;
        data.unleashPoint = 1;
        MoveData.StatusEffectData bad = new MoveData.StatusEffectData();
        bad.summonCharacterId = "DOG";
        bad.codedAbilityKey = "RATIO";
        bad.codedAction = "CREATE_STACKS";
        data.selfEffects = List.of(bad);
        assertThrows(IllegalArgumentException.class, data::toMove);
    }

    @Test
    void summonEffectRowSurvivesMoveDataRoundTrip() {
        Move move = new Move.Builder("SELF_SUMMON")
            .name("Summon Dogs").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .selfEffects(List.of(new StatusEffect("000010")))
            .build();

        Move restored = MoveData.fromMove(move).toMove();
        assertEquals(1, restored.getSelfEffects().size());
        assertTrue(restored.getSelfEffects().get(0).isSummon());
        assertEquals("000010", restored.getSelfEffects().get(0).getSummonCharacterId());
    }

    @Test
    void selfEffectSummonEnqueuesShikigamiOnUnleash() {
        BattleCombatant summoner = fighter("Megumi");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        BattleCharacterLookup dogLookup = id -> Optional.of(shikigami("Divine Dog"));
        Move summonMove = new Move.Builder("SUMMON")
            .name("Summon Dogs").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .selfEffects(List.of(new StatusEffect("DOG")))
            .build();
        BattlePlan plan = new BattlePlan(summoner.getMaxApBar(), summoner.getCurrentCe(), 60);
        plan.place(summonMove, 1, 0);
        summoner.setTimeline(plan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(1L), dogLookup);
        List<CombatEvent> events = resolver.resolveRound(state);

        long summons = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count();
        assertEquals(1, summons, "self-effect summon enqueued exactly one shikigami");
        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_SUMMON),
            "a MOVE_SUMMON event was emitted");
        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.COMBATANT_SUMMONED),
            "the shikigami was materialized (COMBATANT_SUMMONED)");
    }

    @Test
    void onHitSummonEffectEnqueuesShikigamiWhenTheHitConnects() {
        BattleCombatant summoner = fighter("Megumi");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        BattleCharacterLookup dogLookup = id -> Optional.of(shikigami("Divine Dog"));
        Move attackSummon = new Move.Builder("ATK_SUMMON")
            .name("Striking Summon").category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(
                10, Set.of(MoveTag.PHYSICAL), 0, false, true,
                1.0, List.of(new StatusEffect("DOG")))))
            .build();
        BattlePlan plan = new BattlePlan(summoner.getMaxApBar(), summoner.getCurrentCe(), 60);
        plan.place(attackSummon, 1, 0, enemy.getInstanceId());
        summoner.setTimeline(plan.toLegacyTimeline());

        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(1L), dogLookup);
        List<CombatEvent> events = resolver.resolveRound(state);

        long summons = state.playerTeam().all().stream()
            .filter(BattleCombatant::isSummon).count();
        assertTrue(summons >= 1, "on-hit summon effect enqueued a shikigami");
        assertTrue(events.stream().anyMatch(e -> e.getType() == CombatEvent.Type.DAMAGE_DEALT
            && e.getTarget() == enemy), "the attack still dealt damage");
    }

    @Test
    void summonEffectRowSkipsUnknownId() {
        BattleCombatant summoner = fighter("Megumi");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        Move summonMove = new Move.Builder("SUMMON")
            .name("Summon").category(MoveCategory.UTILITY)
            .apCost(2).unleashPoint(1)
            .selfEffects(List.of(new StatusEffect("MISSING")))
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

    // --- fixtures ---

    private static BattleCombatant fighter(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(c, List.of());
    }

    private static ShikigamiCharacter shikigami(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        return new ShikigamiCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
    }
}
