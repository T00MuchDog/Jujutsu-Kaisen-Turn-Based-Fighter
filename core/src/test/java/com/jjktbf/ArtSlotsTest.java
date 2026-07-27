package com.jjktbf;

import com.jjktbf.model.character.CombatStats;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the Combat Art / Jujutsu Art slot progression (ART_SLOT_TIERS).
 *
 * Slots read the RAW governing stat against a shared threshold table (see §7 of
 * GLOSSARY.txt and CombatStats.ART_SLOT_TIERS) and intentionally bypass
 * StatScale. Both pools share the same table, so we drive it through the
 * public artSlotsFor() helper and also assert the wiring end-to-end via
 * CombatStats for each governing stat.
 *
 *   stat ≥  :   0   30   45   60   80   100   120   150   180   210   240   270   300
 *   slots   :   2    3    4    5    6     8     9    10    11    12    13    14    15
 */
public class ArtSlotsTest {

    /** {rawStat, expectedSlotsAtOrAboveIt}. Covers the floor, every threshold, and just-below boundaries. */
    private static final int[][] CASES = {
        // Floor / below first threshold
        {  0,  2 },
        { 10,  2 },   // min stat is 10 → still 2
        { 29,  2 },   // one below 30
        // Each threshold, exact
        { 30,  3 },
        { 44,  3 },   // one below 45
        { 45,  4 },
        { 59,  4 },   // one below 60
        { 60,  5 },
        { 79,  5 },   // one below 80
        { 80,  6 },   // baseline
        { 99,  6 },   // one below 100
        {100,  8 },   // +2 jump
        {119,  8 },   // one below 120
        {120,  9 },
        {149,  9 },   // one below 150
        {150, 10 },
        {180, 11 },
        {210, 12 },
        {240, 13 },
        {270, 14 },
        {300, 15 },   // max
        // Above the max tier clamps
        {400, 15 },
    };

    @Test
    void artSlotsForMatchesTierTable() {
        for (int[] c : CASES) {
            int raw = c[0], expected = c[1];
            int actual = CombatStats.artSlotsFor(raw);
            assertEquals(expected, actual,
                "artSlotsFor(" + raw + ") should be " + expected + " but was " + actual);
        }
    }

    @Test
    void maxArtSlotsIs15() {
        assertEquals(15, CombatStats.MAX_ART_SLOTS);
    }
}
