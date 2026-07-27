package com.jjktbf.model.character;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatTierTest {

    @Test
    void exposesTheRequestedStatRanges() {
        assertRange(StatTier.GRADE_4, "Grade 4", 10, 30);
        assertRange(StatTier.GRADE_3, "Grade 3", 30, 60);
        assertRange(StatTier.GRADE_2, "Grade 2", 60, 80);
        assertRange(StatTier.SEMI_GRADE_1, "Semi-Grade 1", 80, 100);
        assertRange(StatTier.GRADE_1, "Grade 1", 100, 150);
        assertRange(StatTier.HEAVY_HITTER, "Heavy Hitter", 150, 200);
        assertRange(StatTier.SPECIAL_GRADE, "Special Grade", 200, 250);
        assertRange(StatTier.CALAMITY, "Calamity", 250, 300);
    }

    @Test
    void classifiesBaseStatTotalsUsingTenStatMaximums() {
        assertEquals(StatTier.GRADE_4, StatTier.forBaseStatTotal(300));
        assertEquals(StatTier.GRADE_3, StatTier.forBaseStatTotal(301));
        assertEquals(StatTier.GRADE_3, StatTier.forBaseStatTotal(600));
        assertEquals(StatTier.GRADE_2, StatTier.forBaseStatTotal(800));
        assertEquals(StatTier.SEMI_GRADE_1, StatTier.forBaseStatTotal(1000));
        assertEquals(StatTier.GRADE_1, StatTier.forBaseStatTotal(1500));
        assertEquals(StatTier.HEAVY_HITTER, StatTier.forBaseStatTotal(2000));
        assertEquals(StatTier.SPECIAL_GRADE, StatTier.forBaseStatTotal(2500));
        assertEquals(StatTier.CALAMITY, StatTier.forBaseStatTotal(3000));
    }

    private static void assertRange(StatTier tier, String name, int minimum, int maximum) {
        assertEquals(name, tier.displayName());
        assertEquals(minimum, tier.minimumStat());
        assertEquals(maximum, tier.maximumStat());
        assertEquals(maximum * 10, tier.maximumBaseStatTotal());
    }
}
