package com.jjktbf;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.MoveTargeting;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Phase 3 coverage for canonical plans and targets: target on ActionSegment,
 * target-aware placement, target survival through {@code toLegacyTimeline()},
 * the derived targeting enum, and team-plan target validation.
 */
class PlanTargetingTest {

    @Test
    void singleTargetMoveRequiresTarget() {
        Move attack = physicalAttack("ATK");
        assertTrue(BattlePlan.requiresTarget(attack));
        assertEquals(MoveTargeting.SINGLE_ENEMY, MoveTargeting.forMove(attack));
    }

    @Test
    void defensiveMoveRequiresNoTarget() {
        Move block = fullBlock("BLK");
        assertFalse(BattlePlan.requiresTarget(block));
        assertEquals(MoveTargeting.NONE, MoveTargeting.forMove(block));
    }

    @Test
    void aoeMoveIsAllEnemies() {
        Move aoe = aoeAttack("AOE", false);
        assertEquals(MoveTargeting.ALL_ENEMIES, MoveTargeting.forMove(aoe));
        assertFalse(BattlePlan.requiresTarget(aoe));
        assertTrue(MoveTargeting.forMove(aoe).isAreaOfEffect());
    }

    @Test
    void friendlyFireAoeIsAllOthers() {
        Move ffAoe = aoeAttack("FF", true);
        assertEquals(MoveTargeting.ALL_OTHERS, MoveTargeting.forMove(ffAoe));
        assertTrue(MoveTargeting.forMove(ffAoe).isAreaOfEffect());
    }

    @Test
    void targetSurvivesPlacementDragFlattenAndCopy() {
        BattleCombatant player = fighter("Yuji");
        BattleCombatant enemy  = fighter("Sukuna");
        BattleState state = new BattleState(player, enemy);
        CombatantId enemyId = enemy.getInstanceId();

        Move attack = physicalAttack("ATK");
        BattlePlan plan = new BattlePlan(player.getMaxApBar(), player.getCurrentCe(), 60);
        ActionSegment placed = plan.place(attack, 1, 0, enemyId);

        assertNotNull(placed);
        assertEquals(enemyId, placed.getTarget());

        // Target survives the legacy-timeline flatten (resolver merge).
        var legacy = plan.toLegacyTimeline();
        ActionSegment merged = legacy.getSegments().get(0);
        assertEquals(enemyId, merged.getTarget(),
            "target id must survive toLegacyTimeline()");

        // Target survives a remove + re-place cycle (drag cancellation).
        assertTrue(plan.remove(placed));
        ActionSegment replaced = plan.place(attack, 1, 0, enemyId);
        assertEquals(enemyId, replaced.getTarget());
    }

    @Test
    void incompleteTargetIsPermittedWhileEditingButRejectsLock() {
        BattleCombatant player = fighter("Yuji");
        BattleCombatant enemy  = fighter("Sukuna");
        new BattleState(player, enemy);

        Move attack = physicalAttack("ATK");
        BattlePlan plan = new BattlePlan(player.getMaxApBar(), player.getCurrentCe(), 60);

        // Placing without a target is allowed (incomplete while editing)...
        ActionSegment placed = plan.place(attack, 1, 0, null);
        assertNotNull(placed);
        assertNull(placed.getTarget());
        // ...but locking/submission is rejected.
        assertNotNull(plan.missingTargetError());

        // Completing the target clears the error.
        placed.setTarget(enemy.getInstanceId());
        assertNull(plan.missingTargetError());
    }

    @Test
    void teamPlanValidatesTargetsAcrossAllActors() {
        BattleCombatant a = fighter("Yuji");
        BattleCombatant b = fighter("Nanami");
        BattleCombatant enemy = fighter("Sukuna");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(a, b)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));

        TeamBattlePlan teamPlan = new TeamBattlePlan(
            BattleTeamId.PLAYER, TeamBattlePlan.gridLengthForRound(state));
        Move attack = physicalAttack("ATK");

        BattlePlan planA = new BattlePlan(a.getMaxApBar(), a.getCurrentCe(), teamPlan.gridLength());
        planA.place(attack, 1, 0, enemy.getInstanceId());
        teamPlan.put(a.getInstanceId(), planA);

        BattlePlan planB = new BattlePlan(b.getMaxApBar(), b.getCurrentCe(), teamPlan.gridLength());
        planB.place(attack, 1, 0, null);   // missing target
        teamPlan.put(b.getInstanceId(), planB);

        assertNotNull(teamPlan.missingTargetError());
        assertNotNull(teamPlan.validationError(state));

        planB.allSegments().get(0).setTarget(enemy.getInstanceId());
        assertNull(teamPlan.missingTargetError());
        assertNull(teamPlan.validationError(state));
    }

    @Test
    void authoritativeTeamPlanValidationRejectsOmissionsTeamsGridsAndAlliedTargets() {
        BattleCombatant a = fighter("A");
        BattleCombatant b = fighter("B");
        BattleCombatant enemy = fighter("Enemy");
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(a, b)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
        int grid = TeamBattlePlan.gridLengthForRound(state);

        TeamBattlePlan omitted = new TeamBattlePlan(BattleTeamId.PLAYER, grid);
        omitted.put(a.getInstanceId(), new BattlePlan(a.getMaxApBar(), a.getCurrentCe(), grid));
        assertNotNull(omitted.validationError(state));

        TeamBattlePlan wrongTeam = new TeamBattlePlan(BattleTeamId.ENEMY, grid);
        wrongTeam.put(a.getInstanceId(), new BattlePlan(a.getMaxApBar(), a.getCurrentCe(), grid));
        assertNotNull(wrongTeam.validationError(state));

        TeamBattlePlan wrongGrid = new TeamBattlePlan(BattleTeamId.PLAYER, grid + 1);
        wrongGrid.put(a.getInstanceId(), new BattlePlan(a.getMaxApBar(), a.getCurrentCe(), grid + 1));
        wrongGrid.put(b.getInstanceId(), new BattlePlan(b.getMaxApBar(), b.getCurrentCe(), grid + 1));
        assertNotNull(wrongGrid.validationError(state));

        TeamBattlePlan alliedTarget = new TeamBattlePlan(BattleTeamId.PLAYER, grid);
        BattlePlan aPlan = new BattlePlan(a.getMaxApBar(), a.getCurrentCe(), grid);
        aPlan.place(physicalAttack("ALLY_TARGET"), 1, 0, b.getInstanceId());
        alliedTarget.put(a.getInstanceId(), aPlan);
        alliedTarget.put(b.getInstanceId(),
            new BattlePlan(b.getMaxApBar(), b.getCurrentCe(), grid));
        assertNotNull(alliedTarget.validationError(state));
    }

    @Test
    void teamPlanGridLengthDerivedFromMaxApOfAllActiveCombatants() {
        // A fighter with maxed speed/combatAbility produces AP > 150 → grid tier 300.
        BattleCombatant strong = fighterWithStats("Strong", 300, 300);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(fighter("Weak"))),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(strong)));

        int grid = TeamBattlePlan.gridLengthForRound(state);
        assertEquals(300, grid, "grid length tracks the strongest active combatant's AP tier");
        assertTrue(strong.getMaxApBar() > 150,
            "sanity: maxed stats should produce AP > 150, got " + strong.getMaxApBar());
    }

    @Test
    void teamPlanKeyedByActorInstanceIdNotDefinition() {
        // Duplicate summons of the same definition must be distinguishable by instance id.
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        var lookup = new com.jjktbf.model.combat.BattleCharacterLookup() {
            @Override public java.util.Optional<com.jjktbf.model.character.Character> findCharacter(String id) {
                return java.util.Optional.of(shikigami("Dog"));
            }
        };
        state.enqueueSummon(player, "DOG");
        state.enqueueSummon(player, "DOG");
        List<BattleCombatant> dogs = state.drainPendingSummons(lookup);

        TeamBattlePlan teamPlan = new TeamBattlePlan(
            BattleTeamId.PLAYER, TeamBattlePlan.gridLengthForRound(state));
        teamPlan.put(dogs.get(0).getInstanceId(), new BattlePlan(60, 0, 60));
        teamPlan.put(dogs.get(1).getInstanceId(), new BattlePlan(60, 0, 60));

        assertEquals(2, teamPlan.size());
        assertNotEquals(teamPlan.get(dogs.get(0).getInstanceId()),
            teamPlan.get(dogs.get(1).getInstanceId()));
    }

    private static BattleCombatant fighter(String name) {
        return fighter(name, 80);
    }

    private static BattleCombatant fighter(String name, int speed) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(speed).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(c, List.of());
    }

    private static BattleCombatant fighterWithStats(String name, int speed, int combatAbility) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(speed).combatAbility(combatAbility).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(c, List.of());
    }

    private static ShikigamiCharacter shikigami(String name) {
        return new ShikigamiCharacter(
            name.toLowerCase(), name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), Equipment.NONE);
    }

    private static Move physicalAttack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .neverMiss(true)
            .apCost(2)
            .unleashPoint(1)
            .hitComponents(List.of(new HitComponent(
                10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move aoeAttack(String id, boolean friendlyFire) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .neverMiss(true)
            .apCost(2)
            .unleashPoint(1)
            .tags(friendlyFire
                ? Set.of(MoveTag.PHYSICAL, MoveTag.AOE, MoveTag.FRIENDLY_FIRE)
                : Set.of(MoveTag.PHYSICAL, MoveTag.AOE))
            .hitComponents(List.of(new HitComponent(
                10, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move fullBlock(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(100)
            .blockAffectedTags(List.of("PHYSICAL"))
            .apCost(5)
            .unleashPoint(1)
            .build();
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(java.util.Objects.equals(a, b));
    }
}
