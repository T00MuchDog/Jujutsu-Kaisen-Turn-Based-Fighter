package com.jjktbf.graphics.screens;

import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.BattleSpriteScaleConfig;
import com.jjktbf.graphics.ui.CombatantPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleScreenSpriteBoundsTest {

    @Test
    void scaledSpriteKeepsItsCenterAndBottomAnchor() {
        Rectangle bounds = BattleScreen.scaledSpriteBounds(
            160f, 42f, 80f, BattleSpriteScaleConfig.Scale.X_1_5.factor());

        assertEquals(100f, bounds.x, 0.0001f);
        assertEquals(42f, bounds.y, 0.0001f);
        assertEquals(120f, bounds.width, 0.0001f);
        assertEquals(120f, bounds.height, 0.0001f);
        assertEquals(160f, bounds.x + bounds.width / 2f, 0.0001f);
    }

    @Test
    void spriteDrawOrderPlacesTheRightmostFighterInFront() {
        assertTrue(BattleScreen.primarySpriteDrawsFirst(120f, 200f));
        assertFalse(BattleScreen.primarySpriteDrawsFirst(200f, 120f));
    }

    @Test
    void threeFighterFormationCentersFirstAndEvenlySpacesTeammates() {
        assertEquals(0f, BattleScreen.formationOffset(0, 3, 80f), 0.0001f);
        assertEquals(80f, BattleScreen.formationOffset(1, 3, 80f), 0.0001f);
        assertEquals(-80f, BattleScreen.formationOffset(2, 3, 80f), 0.0001f);
    }

    @Test
    void fourFighterFormationPlacesThirdLeftmostAndFourthRightmost() {
        assertEquals(-40f, BattleScreen.formationOffset(0, 4, 80f), 0.0001f);
        assertEquals(40f, BattleScreen.formationOffset(1, 4, 80f), 0.0001f);
        assertEquals(-120f, BattleScreen.formationOffset(2, 4, 80f), 0.0001f);
        assertEquals(120f, BattleScreen.formationOffset(3, 4, 80f), 0.0001f);
    }

    @Test
    void plateAndHudScalingOnlyChangeAtThreeAndFourFighters() {
        assertEquals(1f, BattleScreen.plateScale(1), 0.0001f);
        assertEquals(1f, BattleScreen.plateScale(2), 0.0001f);
        assertEquals(1.5f, BattleScreen.plateScale(3), 0.0001f);
        assertEquals(2f, BattleScreen.plateScale(4), 0.0001f);

        assertEquals(1f, BattleScreen.hudWidthScale(1), 0.0001f);
        assertEquals(1f, BattleScreen.hudWidthScale(2), 0.0001f);
        assertEquals(0.5f, BattleScreen.hudWidthScale(3), 0.0001f);
        assertEquals(0.5f, BattleScreen.hudWidthScale(4), 0.0001f);
    }

    @Test
    void twoFighterFormationRetainsLegacySideOrientation() {
        assertEquals(-17f, BattleScreen.fighterOffset(0, 2, 100f, false), 0.0001f);
        assertEquals(17f, BattleScreen.fighterOffset(1, 2, 100f, false), 0.0001f);
        assertEquals(17f, BattleScreen.fighterOffset(0, 2, 100f, true), 0.0001f);
        assertEquals(-17f, BattleScreen.fighterOffset(1, 2, 100f, true), 0.0001f);
    }

    @Test
    void enemyFourFighterShiftRetainsMirroredLeftDelta() {
        float shift = BattleScreen.enemyFourFighterLeftShift(20f, 600f, 300f);

        assertEquals(16f, shift, 0.0001f);
        assertEquals(20f, 300f - shift - 300f + 36f, 0.0001f);
    }

    @Test
    void enemyPlateMovesAbovePlayerHudWithClearance() {
        assertEquals(22f,
            BattleScreen.enemyPlateClearanceShift(0f, 100f, 48f), 0.0001f);
        assertEquals(0f,
            BattleScreen.enemyPlateClearanceShift(40f, 100f, 48f), 0.0001f);
    }

    @Test
    void playerGroupMovesHalfItsRightEdgeGap() {
        assertEquals(20f,
            BattleScreen.halfRightEdgeGap(1000f, 760f, 200f), 0.0001f);
        assertEquals(0f,
            BattleScreen.halfRightEdgeGap(1000f, 810f, 200f), 0.0001f);
    }

    @Test
    void hudRowsFillTopDownForPlayerAndBottomUpForEnemy() {
        assertEquals(200f, BattleScreen.hudRowY(200f, 0, 100f, 10f, false), 0.0001f);
        assertEquals(90f, BattleScreen.hudRowY(200f, 1, 100f, 10f, false), 0.0001f);
        assertEquals(200f, BattleScreen.hudRowY(200f, 0, 100f, 10f, true), 0.0001f);
        assertEquals(310f, BattleScreen.hudRowY(200f, 1, 100f, 10f, true), 0.0001f);
    }

    @Test
    void singleHudIsCenteredBetweenTwoFighterRows() {
        assertEquals(145f, BattleScreen.centeredHudY(200f, 100f, 10f), 0.0001f);
    }

    @Test
    void windowsHudWidthIsConstrainedAfterScaling() {
        float inwardOffset = 44.8f + 23.04f;
        float width = BattleScreen.scaledHudWidth(
            600f, 1.25f, 1280f, 32f, 12f, inwardOffset, true);
        assertEquals(534.16f, width, 0.0001f);
        float availableCenterGap = 1280f - 64f - width * 2f;
        float shift = Math.min(44.8f, (availableCenterGap - 12f) / 2f);
        assertEquals(12f,
            availableCenterGap - (shift + 23.04f) * 2f, 0.0001f);
        assertEquals(750f,
            BattleScreen.scaledHudWidth(
                600f, 1.25f, 1280f, 32f, 12f, inwardOffset, false),
            0.0001f);
    }

    @Test
    void speedControlsAlignAboveTheNextRoundButton() {
        Rectangle nextRound = new Rectangle(800f, 17f, 210f, 54f);
        Rectangle fastForward = new Rectangle();
        Rectangle skip = new Rectangle();

        BattleScreen.layoutSpeedControls(nextRound, 153f, fastForward, skip);

        assertEquals(54f, fastForward.width, 0.0001f);
        assertEquals(54f, fastForward.height, 0.0001f);
        assertEquals(8f, skip.x - fastForward.x - fastForward.width, 0.0001f);
        assertEquals(nextRound.x + nextRound.width, skip.x + skip.width, 0.0001f);
        assertTrue(fastForward.y > nextRound.y + nextRound.height);
        assertTrue(skip.y + skip.height < 153f);
    }

    @Test
    void windowsNextRoundGeometryDoesNotEnlargeSpeedIcons() {
        Rectangle nextRound = new Rectangle(900f, 21f, 315f, 81f);
        Rectangle fastForward = new Rectangle();
        Rectangle skip = new Rectangle();

        BattleScreen.layoutSpeedControls(nextRound, 218f, fastForward, skip);

        assertEquals(54f, fastForward.width, 0.0001f);
        assertEquals(54f, skip.width, 0.0001f);
    }

    @Test
    void faintSlideQuicklyPassesBelowTheOriginalFootLine() {
        assertEquals(0f, BattleScreen.faintSlideRatio(0f), 0.0001f);
        assertEquals(0.75f, BattleScreen.faintSlideRatio(0.5f), 0.0001f);
        assertEquals(1f, BattleScreen.faintSlideRatio(1f), 0.0001f);
        assertEquals(0f, BattleScreen.faintSlideRatio(-1f), 0.0001f);
        assertEquals(1f, BattleScreen.faintSlideRatio(2f), 0.0001f);
    }

    @Test
    void summonEntranceRiseStartsFullyBelowTheScreenBottom() {
        // Final footing y=40 with a 100px sprite: 140px of travel.
        assertEquals(140f, CombatantPanel.entranceRiseOffset(40f, 100f, 0f), 0.0001f);
        assertEquals(35f, CombatantPanel.entranceRiseOffset(40f, 100f, 0.75f), 0.0001f);
        assertEquals(0f, CombatantPanel.entranceRiseOffset(40f, 100f, 1f), 0.0001f);
        assertEquals(0f, CombatantPanel.entranceRiseOffset(40f, 100f, 2f), 0.0001f);
    }

    @Test
    void summonEntranceGrowsFromTinyToFullScale() {
        assertEquals(0.1f, CombatantPanel.entranceGrowScale(0f), 0.0001f);
        assertEquals(0.55f, CombatantPanel.entranceGrowScale(0.5f), 0.0001f);
        assertEquals(1f, CombatantPanel.entranceGrowScale(1f), 0.0001f);
        assertEquals(1f, CombatantPanel.entranceGrowScale(2f), 0.0001f);
    }

}
