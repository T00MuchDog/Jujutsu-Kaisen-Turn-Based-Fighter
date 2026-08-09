package com.jjktbf.graphics.screens;

import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.BattleSpriteScaleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
