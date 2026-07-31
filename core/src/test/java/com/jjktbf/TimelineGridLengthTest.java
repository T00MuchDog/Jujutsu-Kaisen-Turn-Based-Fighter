package com.jjktbf;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.Timeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
