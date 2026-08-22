package com.jjktbf;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCharacterLookup;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeam;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.CombatantRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Phase 2 coverage for the team and runtime-identity model: deterministic
 * instance ids, duplicate summons, team win conditions, summoner-death
 * recursive dismissal, and 2v2 construction.
 */
class TeamBattleStateTest {

    @Test
    void stateRequiresPlayerAndEnemyTeamsInTheirAuthoritativeFields() {
        BattleTeam players = BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(fighter("Player")));
        BattleTeam enemies = BattleState.teamOfFighters(
            BattleTeamId.ENEMY, List.of(fighter("Enemy")));

        assertThrows(IllegalArgumentException.class,
            () -> new BattleState(enemies, players));
        assertThrows(IllegalArgumentException.class,
            () -> new BattleTeam(new BattleTeamId("SPECTATOR")));
    }

    @Test
    void combatantIdentityCannotBeReusedForAnotherRosterSlot() {
        BattleCombatant duplicate = fighter("Duplicate");

        assertThrows(IllegalStateException.class, () -> BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(duplicate, duplicate)));
    }

    @Test
    void teamLookupRequiresActualMembershipRatherThanAnEqualInstanceId() {
        BattleCombatant member = fighter("Member");
        BattleState state = new BattleState(member, fighter("Enemy"));
        BattleCombatant foreignWithSameGeneratedId = fighter("Foreign");
        new BattleState(foreignWithSameGeneratedId, fighter("Other Enemy"));

        assertEquals(member.getInstanceId(), foreignWithSameGeneratedId.getInstanceId());
        assertNull(state.teamOf(foreignWithSameGeneratedId));
        assertEquals(member, state.combatant(foreignWithSameGeneratedId.getInstanceId()));
    }

    @Test
    void legacy1v1ConstructorAssignsDistinctInstanceIdsAndRoles() {
        BattleCombatant player = fighter("Yuji");
        BattleCombatant enemy  = fighter("Sukuna");
        BattleState state = new BattleState(player, enemy);

        assertNotNull(player.getInstanceId());
        assertNotNull(enemy.getInstanceId());
        assertNotEquals(player.getInstanceId(), enemy.getInstanceId());
        assertEquals(CombatantRole.FIGHTER, player.getRole());
        assertEquals(CombatantRole.FIGHTER, enemy.getRole());
        assertEquals(BattleTeamId.PLAYER, player.getTeamId());
        assertEquals(BattleTeamId.ENEMY, enemy.getTeamId());
        assertEquals(player, state.getPlayerCombatant());
        assertEquals(enemy, state.getEnemyCombatant());
    }

    @Test
    void duplicateSummonsReceiveDistinctInstanceIds() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        BattleCharacterLookup divineDog = id -> Optional.of(shikigami("Divine Dog"));
        state.enqueueSummon(player, "DOG");
        state.enqueueSummon(player, "DOG");
        List<BattleCombatant> created = state.drainPendingSummons(divineDog);

        assertEquals(2, created.size());
        assertNotEquals(created.get(0).getInstanceId(), created.get(1).getInstanceId());
        for (BattleCombatant summon : created) {
            assertEquals(CombatantRole.SUMMON, summon.getRole());
            assertEquals(BattleTeamId.PLAYER, summon.getTeamId());
            assertEquals(player.getInstanceId(), summon.getSummonerId());
            assertTrue(summon.isActive());
            // Summons begin with full resources.
            assertEquals(summon.getMaxHp(), summon.getCurrentHp());
            assertEquals(summon.getMaxCursedEnergy(), summon.getCurrentCe());
        }
    }

    @Test
    void generatedIdsAreDeterministicAndDoNotCollideWithSeededRosterIds() {
        List<CombatantId> first = generatedJoinIds();
        List<CombatantId> second = generatedJoinIds();

        assertEquals(first, second);
        assertEquals(List.of(new CombatantId("PLAYER-1"), new CombatantId("PLAYER-2")), first);
    }

    @Test
    void summonDeathRemovesItWithoutEndingBattle() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        BattleCharacterLookup dog = id -> Optional.of(shikigami("Divine Dog"));
        state.enqueueSummon(player, "DOG");
        BattleCombatant summon = state.drainPendingSummons(dog).get(0);

        // The summon's team still has a living fighter, so the battle continues.
        assertFalse(state.checkAndResolveBattleOver());

        summon.receiveDamage(summon.getCurrentHp());
        assertTrue(summon.isDefeated());
        List<BattleCombatant> changed = state.reconcileDefeats();

        assertTrue(changed.contains(summon));
        assertTrue(summon.isRemoved(), "a defeated summon is removed from active combat");
        assertFalse(summon.isActive());
        assertFalse(state.checkAndResolveBattleOver(),
            "defeating a summon must NOT end the battle while a fighter lives");
    }

    @Test
    void summonerDeathRecursivelyDismissesItsSummons() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        BattleCharacterLookup dog = id -> Optional.of(shikigami("Divine Dog"));
        state.enqueueSummon(player, "DOG");
        state.enqueueSummon(player, "DOG");
        state.drainPendingSummons(dog);

        // Defeat the summoner (a fighter). Its summons must be dismissed.
        player.receiveDamage(player.getCurrentHp());
        state.reconcileDefeats();

        assertTrue(player.isLifecycleDefeated());
        for (BattleCombatant c : state.playerTeam().all()) {
            if (c.isSummon()) {
                assertTrue(c.isRemoved(),
                    "summons owned by a defeated summoner must be dismissed");
            }
        }
    }

    @Test
    void oneFighterDyingIn2v2DoesNotEndCombat() {
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER,
            List.of(fighter("Yuji"), fighter("Nanami")));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY,
            List.of(fighter("Sukuna")));
        BattleState state = new BattleState(players, enemies);

        BattleCombatant yuji = players.all().get(0);
        yuji.receiveDamage(yuji.getCurrentHp());
        state.reconcileDefeats();

        assertTrue(yuji.isLifecycleDefeated());
        assertFalse(state.checkAndResolveBattleOver(),
            "a team with one living fighter is not yet defeated");
    }

    @Test
    void teamLosesOnlyWhenEveryFighterIsDefeated() {
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER,
            List.of(fighter("Yuji"), fighter("Nanami")));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY,
            List.of(fighter("Sukuna")));
        BattleState state = new BattleState(players, enemies);

        BattleCombatant yuji = players.all().get(0);
        BattleCombatant nanami = players.all().get(1);

        yuji.receiveDamage(yuji.getCurrentHp());
        state.reconcileDefeats();
        assertFalse(state.checkAndResolveBattleOver());

        nanami.receiveDamage(nanami.getCurrentHp());
        state.reconcileDefeats();
        assertTrue(state.checkAndResolveBattleOver());
        assertEquals(BattleTeamId.ENEMY, state.getWinnerTeam());
    }

    @Test
    void simultaneousFighterEliminationProducesDraw() {
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER,
            List.of(fighter("Yuji")));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY,
            List.of(fighter("Sukuna")));
        BattleState state = new BattleState(players, enemies);

        BattleCombatant yuji = players.all().get(0);
        BattleCombatant sukuna = enemies.all().get(0);
        yuji.receiveDamage(yuji.getCurrentHp());
        sukuna.receiveDamage(sukuna.getCurrentHp());
        state.reconcileDefeats();

        assertTrue(state.checkAndResolveBattleOver());
        assertNull(state.getWinnerTeam(), "simultaneous wipe is a draw");
        assertNull(state.getWinner());
    }

    @Test
    void activeEnemiesOfReturnsOpposingTeamInStableOrder() {
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER,
            List.of(fighter("Yuji")));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY,
            List.of(fighter("Sukuna"), fighter("Jogo")));
        BattleState state = new BattleState(players, enemies);

        BattleCombatant yuji = players.all().get(0);
        List<BattleCombatant> enemiesOfYuji = state.activeEnemiesOf(yuji);
        assertEquals(2, enemiesOfYuji.size());
        assertEquals("Sukuna", enemiesOfYuji.get(0).getCharacter().getName());
        assertEquals("Jogo", enemiesOfYuji.get(1).getCharacter().getName());
    }

    @Test
    void firstActiveEnemyIsDeterministicRetargetFallback() {
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER,
            List.of(fighter("Yuji")));
        BattleTeam enemies = BattleState.teamOfFighters(BattleTeamId.ENEMY,
            List.of(fighter("Sukuna"), fighter("Jogo")));
        BattleState state = new BattleState(players, enemies);

        BattleCombatant yuji = players.all().get(0);
        BattleCombatant first = state.firstActiveEnemyOf(yuji);
        assertNotNull(first);
        assertEquals("Sukuna", first.getCharacter().getName());
    }

    @Test
    void livingSummonsDoNotPreventTeamDefeat() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        BattleCharacterLookup dog = id -> Optional.of(shikigami("Divine Dog"));
        state.enqueueSummon(player, "DOG");
        BattleCombatant summon = state.drainPendingSummons(dog).get(0);
        assertTrue(summon.isActive());

        // Defeat the only fighter; the living summon must not save the team.
        player.receiveDamage(player.getCurrentHp());
        state.reconcileDefeats();
        assertTrue(state.checkAndResolveBattleOver());
        assertEquals(BattleTeamId.ENEMY, state.getWinnerTeam());
    }

    @Test
    void enqueueSummonDoesNotImmediatelyAddToActiveCombatants() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        int before = state.activeCombatants().size();
        state.enqueueSummon(player, "DOG");
        assertEquals(before, state.activeCombatants().size(),
            "pending summons must not join the firing list / AOE snapshot");
        assertEquals(1, state.pendingSummons().size());
    }

    @Test
    void combatantLookupByInstanceId() {
        BattleCombatant player = fighter("Yuji");
        BattleCombatant enemy  = fighter("Sukuna");
        BattleState state = new BattleState(player, enemy);

        assertEquals(player, state.combatant(player.getInstanceId()));
        assertEquals(enemy, state.combatant(enemy.getInstanceId()));
        assertNull(state.combatant(new CombatantId("NONEXISTENT-1")));
        assertTrue(state.findCombatant(player.getInstanceId()).isPresent());
        assertFalse(state.findCombatant(new CombatantId("NOPE-9")).isPresent());
    }

    @Test
    void recursiveDismissalHandlesNestedSummons() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        BattleCharacterLookup lookup = id -> Optional.of(shikigami("Shikigami"));
        // First summon (owned by player).
        state.enqueueSummon(player, "X");
        BattleCombatant summon = state.drainPendingSummons(lookup).get(0);
        // A summon that summons another summon (nested ownership).
        state.enqueueSummon(summon, "Y");
        BattleCombatant nested = state.drainPendingSummons(lookup).get(0);

        assertEquals(summon.getInstanceId(), nested.getSummonerId());

        // Dismissing the top-level summoner cascades to nested summons.
        int dismissed = state.recursivelyDismissSummonsOf(player);
        assertTrue(dismissed >= 2);
        assertTrue(summon.isRemoved());
        assertTrue(nested.isRemoved());
    }

    @Test
    void defeatedSummonDismissesDescendantsAndReconciliationReturnsEveryChange() {
        BattleCombatant player = fighter("Megumi");
        BattleState state = new BattleState(player, fighter("Enemy"));
        BattleCharacterLookup lookup = id -> Optional.of(shikigami(id));
        state.enqueueSummon(player, "PARENT");
        BattleCombatant summon = state.drainPendingSummons(lookup).get(0);
        state.enqueueSummon(summon, "CHILD");
        BattleCombatant child = state.drainPendingSummons(lookup).get(0);

        summon.receiveDamage(summon.getCurrentHp());
        List<BattleCombatant> changed = state.reconcileDefeats();

        assertTrue(changed.contains(summon));
        assertTrue(changed.contains(child));
        assertTrue(summon.isRemoved());
        assertTrue(child.isRemoved());
        assertTrue(player.isActive());
    }

    @Test
    void pendingSummonsAreCancelledWhenTheirSummonerBecomesInactive() {
        BattleCombatant player = fighter("Megumi");
        BattleState state = new BattleState(player, fighter("Enemy"));
        state.enqueueSummon(player, "DOG");

        player.receiveDamage(player.getCurrentHp());
        assertTrue(state.drainPendingSummons(
            id -> Optional.of(shikigami("Dog"))).isEmpty());
        state.reconcileDefeats();

        assertTrue(state.pendingSummons().isEmpty());
    }

    @Test
    void summonMaterializationRejectsNonShikigamiDefinitions() {
        BattleCombatant player = fighter("Megumi");
        BattleState state = new BattleState(player, fighter("Enemy"));
        state.enqueueSummon(player, "NOT_A_SUMMON");

        assertTrue(state.drainPendingSummons(
            id -> Optional.of(fighter("Sorcerer").getCharacter())).isEmpty());
        assertEquals(2, state.allCombatants().size());
    }

    @Test
    void unknownSummonIdIsSkippedWithWarning() {
        BattleCombatant player = fighter("Megumi");
        BattleCombatant enemy  = fighter("Enemy");
        BattleState state = new BattleState(player, enemy);

        state.enqueueSummon(player, "MISSING");
        List<BattleCombatant> created = state.drainPendingSummons(id -> Optional.empty());
        assertTrue(created.isEmpty());
    }

    @Test
    void team2v2ConstructionIsStableOrdered() {
        BattleTeam players = BattleState.teamOfFighters(BattleTeamId.PLAYER,
            List.of(fighter("Yuji"), fighter("Nanami"), fighter("Maki")));
        BattleState state = new BattleState(players,
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("Sukuna"))));

        assertEquals(3, state.playerTeam().size());
        assertEquals(1, state.enemyTeam().size());
        // Roster order is preserved and the first fighter is leftmost.
        assertEquals(0, players.all().get(0).getRosterOrder());
        assertEquals(2, players.all().get(2).getRosterOrder());
        assertEquals("Yuji", state.getPlayerCombatant().getCharacter().getName());
    }

    private static BattleCombatant fighter(String name) {
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(c, List.of());
    }

    private static List<CombatantId> generatedJoinIds() {
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(fighter("Player"))),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("Enemy"))));
        BattleCombatant reinforcement = state.addFighter(
            BattleTeamId.PLAYER, fighter("Reinforcement"));
        state.enqueueSummon(state.getPlayerCombatant(), "DOG");
        BattleCombatant summon = state.drainPendingSummons(
            id -> Optional.of(shikigami("Dog"))).get(0);
        return List.of(reinforcement.getInstanceId(), summon.getInstanceId());
    }

    private static ShikigamiCharacter shikigami(String name) {
        return new ShikigamiCharacter(
            name.toLowerCase(), name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), Equipment.NONE);
    }
}
