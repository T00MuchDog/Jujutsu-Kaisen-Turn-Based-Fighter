package com.jjktbf;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeam;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.MoveTargeting;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.move.AoeType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AoeTagTest {

    @Test
    void movesWithoutAoeAreSingleTarget() {
        Move move = new Move.Builder("SINGLE_TARGET")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .build();

        assertFalse(move.isAoe());
        assertTrue(move.isSingleTarget());
    }

    @Test
    void aoeTagIsQueryableAndSurvivesDataRoundTrip() {
        Move original = new Move.Builder("AOE")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .build();

        assertTrue(original.isAoe());
        assertFalse(original.isSingleTarget());
        assertTrue(original.hasTag("AOE"));

        Move restored = MoveData.fromMove(original).toMove();
        assertTrue(restored.isAoe());
        assertFalse(restored.isSingleTarget());
    }

    @Test
    void friendlyFireCannotBeAuthoredWithoutAoe() {
        assertThrows(IllegalStateException.class, () -> new Move.Builder("INVALID_FF")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.FRIENDLY_FIRE))
            .build());
    }

    @Test
    void aoeTypeDefaultsToAllEnemiesWhenTagPresentButTypeUnset() {
        Move move = new Move.Builder("AOE_DEFAULT")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .build();

        assertEquals(AoeType.ALL_ENEMIES, move.getAoeType(),
            "AOE without an authored type defaults to ALL_ENEMIES");
        assertEquals(MoveTargeting.ALL_ENEMIES, MoveTargeting.forMove(move));
    }

    @Test
    void friendlyFireTagMigratesToAllOthersAoeType() {
        Move move = new Move.Builder("AOE_FF")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE, MoveTag.FRIENDLY_FIRE))
            .build();

        assertEquals(AoeType.ALL_OTHERS, move.getAoeType(),
            "FRIENDLY_FIRE tag migrates to ALL_OTHERS aoeType");
        assertEquals(MoveTargeting.ALL_OTHERS, MoveTargeting.forMove(move));
    }

    @Test
    void multipleAoeTypeRequiresAtLeastTwoTargets() {
        assertThrows(IllegalStateException.class, () -> new Move.Builder("BAD_MULTI")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .aoeType(AoeType.MULTIPLE)
            .aoeTargetCount(1)
            .build());
    }

    @Test
    void aoeTypeRejectedWhenAoeTagAbsent() {
        assertThrows(IllegalStateException.class, () -> new Move.Builder("NO_AOE_TAG")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .aoeType(AoeType.ALL_ENEMIES)
            .build());
    }

    @Test
    void multipleAndAllOthersAoeTypesRoundTripThroughMoveData() {
        Move multiple = new Move.Builder("MULTI")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .aoeType(AoeType.MULTIPLE).aoeTargetCount(3)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        Move restoredMulti = MoveData.fromMove(multiple).toMove();
        assertEquals(AoeType.MULTIPLE, restoredMulti.getAoeType());
        assertEquals(3, restoredMulti.getAoeTargetCount());
        assertEquals(MoveTargeting.MULTIPLE_ENEMIES, MoveTargeting.forMove(restoredMulti));

        Move allOthers = new Move.Builder("ALLOTH")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .aoeType(AoeType.ALL_OTHERS)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        Move restoredOthers = MoveData.fromMove(allOthers).toMove();
        assertEquals(AoeType.ALL_OTHERS, restoredOthers.getAoeType());
        assertEquals(MoveTargeting.ALL_OTHERS, MoveTargeting.forMove(restoredOthers));
    }

    @Test
    void multipleAoeHitsExactlyTheConfiguredNumberOfEnemies() {
        BattleCombatant attacker = fighter("Attacker");
        BattleCombatant ally = fighter("Ally");
        BattleCombatant e1 = fighter("E1");
        BattleCombatant e2 = fighter("E2");
        BattleCombatant e3 = fighter("E3");
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker, ally));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(e1, e2, e3));
        BattleState state = new BattleState(players, enemies);

        Move multi = new Move.Builder("MULTI2")
            .name("Multi2").category(MoveCategory.PHYSICAL).neverMiss(true)
            .apCost(2).unleashPoint(1)
            .tags(Set.of(MoveTag.AOE))
            .aoeType(AoeType.MULTIPLE).aoeTargetCount(2)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        BattlePlan plan = new BattlePlan(attacker.getMaxApBar(), attacker.getCurrentCe(), 60);
        plan.place(multi, 1, 0);
        attacker.setTimeline(plan.toLegacyTimeline());

        int e1Before = e1.getCurrentHp();
        int e2Before = e2.getCurrentHp();
        int e3Before = e3.getCurrentHp();
        int allyBefore = ally.getCurrentHp();
        resolveRound(state);

        assertTrue(e1.getCurrentHp() < e1Before, "first enemy hit by MULTIPLE AOE");
        assertTrue(e2.getCurrentHp() < e2Before, "second enemy hit by MULTIPLE AOE");
        assertEquals(e3Before, e3.getCurrentHp(), "third enemy NOT hit — count is 2");
        assertEquals(allyBefore, ally.getCurrentHp(), "ally NOT hit by MULTIPLE AOE");
    }

    @Test
    void nonAoeMoveHasNullAoeType() {
        Move single = new Move.Builder("SINGLE")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .hitComponents(List.of(new HitComponent(10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
        assertNull(single.getAoeType());
    }

    // --- helpers (mirrors MultiCombatantResolverTest fixtures) ---

    private static BattleCombatant fighter(String name) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(100).build();
        SorcererCharacter character = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(character, List.of());
    }

    private static void resolveRound(BattleState state) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(42));
        resolver.resolveRound(state);
    }
}

