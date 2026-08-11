package com.jjktbf;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the tier → timeline grid-length mapping and that a {@link BattlePlan}
 * adopts the battle-wide grid length it is constructed with.
 */
class TimelineGridLengthTest {

    @Test
    void mapsEachApTierToItsGridLength() {
        assertEquals(60,  Timeline.gridLengthForStrongestAp(0));
        assertEquals(60,  Timeline.gridLengthForStrongestAp(60));
        assertEquals(70,  Timeline.gridLengthForStrongestAp(61));
        assertEquals(70,  Timeline.gridLengthForStrongestAp(70));
        assertEquals(100, Timeline.gridLengthForStrongestAp(71));
        assertEquals(100, Timeline.gridLengthForStrongestAp(100));
        assertEquals(150, Timeline.gridLengthForStrongestAp(101));
        assertEquals(150, Timeline.gridLengthForStrongestAp(150));
        assertEquals(300, Timeline.gridLengthForStrongestAp(151));
        assertEquals(300, Timeline.gridLengthForStrongestAp(300));
        assertEquals(300, Timeline.gridLengthForStrongestAp(Integer.MAX_VALUE));
    }

    @Test
    void battlePlanTwoArgConstructorDerivesGridFromApTier() {
        // apBudget 150 sits in the <= 150 tier → grid 150.
        assertEquals(150, new BattlePlan(150, 1000).gridLength());
        // apBudget 60 sits in the <= 60 tier → grid 60.
        assertEquals(60, new BattlePlan(60, 100).gridLength());
        // apBudget 200 sits above 150 → top tier grid 300.
        assertEquals(300, new BattlePlan(200, 100).gridLength());
    }

    @Test
    void battlePlanExplicitGridOverridesTierDerivation() {
        // A fight where the opponent is stronger: pass the battle-wide grid
        // explicitly so both plans match even when the owner's own AP is lower.
        BattlePlan weakPlayerInTopTierFight = new BattlePlan(60, 100, 300);
        assertEquals(300, weakPlayerInTopTierFight.gridLength());
        assertEquals(300, weakPlayerInTopTierFight.offensiveTimeline().getGridLength());
        assertEquals(300, weakPlayerInTopTierFight.defensiveTimeline().getGridLength());
    }

    @Test
    void legacyTimelineUsesThePlansGridLength() {
        BattlePlan plan = new BattlePlan(60, 100, 70);
        assertEquals(70, plan.toLegacyTimeline().getGridLength());
    }

    @Test
    void fasterSummonExpandsTheTimelineAtTheNextRoundStart() {
        CharacterStats summonerStats = new CharacterStats.Builder()
            .vitality(100)
            .speed(10)
            .combatAbility(10)
            .cursedTechniqueMastery(300)
            .cursedEnergyOutput(300)
            .build();
        BattleCombatant summoner = new BattleCombatant(new SorcererCharacter(
            "summoner", "Summoner", summonerStats, null, List.of(), List.of(), false),
            List.of());
        BattleCombatant enemy = new BattleCombatant(new SorcererCharacter(
            "enemy", "Enemy", new CharacterStats.Builder()
                .vitality(100).speed(10).combatAbility(10).build(),
            null, List.of(), List.of(), false), List.of());
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(summoner)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(enemy)));
        CombatResolver resolver = new CombatResolver(new SeededRandomSource(1L));
        resolver.processRoundStart(state);

        assertEquals(60, TeamBattlePlan.gridLengthForRound(state));

        ShikigamiCharacter fastShikigami = new ShikigamiCharacter(
            "fast", "Fast Shikigami", new CharacterStats.Builder()
                .vitality(100).speed(300).combatAbility(300).build(),
            null, List.of(), List.of(), false);
        state.enqueueSummon(summoner, fastShikigami.getId());
        BattleCombatant summon = state.drainPendingSummons(
            id -> java.util.Optional.of(fastShikigami)).get(0);

        assertEquals(60, TeamBattlePlan.gridLengthForRound(state),
            "a summon created mid-round does not resize already-authored plans");
        state.endRound();
        resolver.processRoundStart(state);

        assertTrue(summon.getMaxApBar() > 150);
        assertEquals(300, TeamBattlePlan.gridLengthForRound(state));
    }
}
