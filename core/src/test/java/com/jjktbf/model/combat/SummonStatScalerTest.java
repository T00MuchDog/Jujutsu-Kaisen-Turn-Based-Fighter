package com.jjktbf.model.combat;

import com.jjktbf.model.character.CharacterStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the summoned-shikigami stat scaling curve.
 *
 * Covers the anchor points of the factor curve (0.5x / 1.0x / 2.0x), the 2:1
 * weighting of the technique stat vs Output, innate vs non-innate stat
 * selection, and the [10, 300] clamping (CTM allows 0).
 */
class SummonStatScalerTest {

    // --- factorFor: the curve itself --------------------------------------

    @Test
    void factorAtPeakGoverningStatIsTwo() {
        // CTM=CEO=300 -> governing 300 -> scaled 472 -> 2.0x
        assertEquals(2.0, SummonStatScaler.factorFor(300, 300), 1e-9);
    }

    @Test
    void factorAtBaselineIsOne() {
        // governing 80 -> scaled 80 -> 1.0x (neutral point)
        assertEquals(1.0, SummonStatScaler.factorFor(80, 80), 1e-9);
    }

    @Test
    void factorAtMinimumGoverningStatIsHalf() {
        // governing 10 -> scaled 10 -> 0.5x
        assertEquals(0.5, SummonStatScaler.factorFor(10, 10), 1e-9);
    }

    @Test
    void factorClampsAboveMinEvenWhenGoverningStatBelowTen() {
        // CTM=0, Output=0 -> governing 0 -> would dip below 0.5, but clamped
        assertEquals(0.5, SummonStatScaler.factorFor(0, 0), 1e-9);
    }

    @Test
    void techniqueStatIsWeightedTwoToOneMoreThanOutput() {
        // Same total, different split: the 2:1 weighting means investing in the
        // technique stat yields a higher factor than the same points in Output.
        double techniqueHeavy = SummonStatScaler.factorFor(300, 0);
        double outputHeavy    = SummonStatScaler.factorFor(0, 300);
        assertTrue(techniqueHeavy > outputHeavy,
            "CTM should weigh twice as heavily as Output");
        assertTrue(techniqueHeavy < 2.0 && outputHeavy < 2.0);
    }

    // --- scale(): the full stat transform ---------------------------------

    @Test
    void maxElephantAtPeakSummonerDoublesRawStats() {
        CharacterStats summoner = allStats(300);          // maxed CTM + Output
        CharacterStats scaled = SummonStatScaler.scale(summoner, maxElephant(), true);

        assertEquals(190, scaled.getVitality(), "VIT 95 x2 = 190");
        assertEquals(1202, baseStatTotal(scaled), "BST 601 x2 = 1202");
        assertEquals(0, scaled.getCursedTechniqueMastery(), "CTM stays 0");
    }

    @Test
    void baselineSummonerLeavesAuthoredStatsUnchanged() {
        CharacterStats summoner = allStats(80);           // baseline across the board
        CharacterStats scaled = SummonStatScaler.scale(summoner, maxElephant(), true);

        assertEquals(95, scaled.getVitality());
        assertEquals(601, baseStatTotal(scaled));
    }

    @Test
    void weakestSummonerHalvesStatsDownToTheTenFloor() {
        CharacterStats summoner = allStats(10);
        CharacterStats scaled = SummonStatScaler.scale(summoner, maxElephant(), true);

        // VIT 95 x0.5 = 47.5 -> 48 (round half up)
        assertEquals(48, scaled.getVitality());
    }

    @Test
    void highStatCapsAt300AndLowStatFloorsAt10() {
        CharacterStats summoner = allStats(300);           // 2.0x
        CharacterStats extreme = new CharacterStats.Builder()
            .vitality(200).strength(10)                    // 200x2=400->300 ; 10x2=20
            .cursedTechniqueMastery(0)
            .build();
        CharacterStats scaled = SummonStatScaler.scale(summoner, extreme, true);

        assertEquals(300, scaled.getVitality(), "200 x2 clamps to MAX_STAT 300");
        assertEquals(20, scaled.getStrength(), "10 x2 = 20 (above the 10 floor)");
    }

    @Test
    void weakestSummonerCannotPushAStatBelowTen() {
        CharacterStats summoner = allStats(10);            // 0.5x
        CharacterStats weak = new CharacterStats.Builder()
            .vitality(10).cursedTechniqueMastery(0).build();
        CharacterStats scaled = SummonStatScaler.scale(summoner, weak, true);

        assertEquals(10, scaled.getVitality(), "10 x0.5 = 5 -> clamps to MIN_STAT 10");
    }

    @Test
    void nonInnateSummonGovernsedByJujutsuSkillNotCtm() {
        // JS=Output=300 -> 2.0x regardless of CTM
        CharacterStats summoner = new CharacterStats.Builder()
            .jujutsuSkill(300).cursedEnergyOutput(300)
            .cursedTechniqueMastery(10)                    // deliberately low/irrelevant
            .build();
        CharacterStats scaled = SummonStatScaler.scale(summoner, maxElephant(), false);

        assertEquals(190, scaled.getVitality(), "JS path at peak also doubles VIT");
    }

    @Test
    void nonInnateSummonIgnoresHighCtm() {
        // High CTM but low JS/Output on a NON-innate summon -> weak, governed by JS
        CharacterStats summoner = new CharacterStats.Builder()
            .cursedTechniqueMastery(300).jujutsuSkill(10).cursedEnergyOutput(10)
            .build();
        CharacterStats scaled = SummonStatScaler.scale(summoner, maxElephant(), false);

        assertEquals(48, scaled.getVitality(), "JS=Output=10 -> 0.5x -> VIT 48");
    }

    // --- fixtures ---------------------------------------------------------

    /** Max Elephant's authored raw base stats (BST 601, VIT 95, CTM 0). */
    private static CharacterStats maxElephant() {
        return new CharacterStats.Builder()
            .vitality(95).strength(75).durability(98).speed(35)
            .cursedEnergyReserves(90).cursedEnergyEfficiency(48).cursedEnergyOutput(88)
            .jujutsuSkill(10).combatAbility(62).cursedTechniqueMastery(0)
            .build();
    }

    /** A summoner whose every stat (incl. CTM, JS, Output) is the same value. */
    private static CharacterStats allStats(int value) {
        return new CharacterStats.Builder()
            .vitality(value).strength(value).durability(value).speed(value)
            .cursedEnergyReserves(value).cursedEnergyEfficiency(value).cursedEnergyOutput(value)
            .jujutsuSkill(value).combatAbility(value).cursedTechniqueMastery(value)
            .build();
    }

    private static int baseStatTotal(CharacterStats s) {
        return s.getVitality() + s.getStrength() + s.getDurability() + s.getSpeed()
            + s.getCursedEnergyReserves() + s.getCursedEnergyEfficiency()
            + s.getCursedEnergyOutput() + s.getJujutsuSkill() + s.getCombatAbility()
            + s.getCursedTechniqueMastery();
    }
}
