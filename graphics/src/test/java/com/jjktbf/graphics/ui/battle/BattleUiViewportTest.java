package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.math.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BattleUiViewportTest {

    @Test
    void windowsCanvasRemains2560By1440InsideSmallerHost() {
        BattleUiViewport viewport = BattleUiViewport.fit(
            2560f, 1440f,
            1440, 900,
            2880, 1800);

        assertEquals(2560f, viewport.logicalWidth());
        assertEquals(1440f, viewport.logicalHeight());
        assertEquals(1440f, viewport.screenWidth(), 0.001f);
        assertEquals(810f, viewport.screenHeight(), 0.001f);
        assertEquals(45f, viewport.screenY(), 0.001f);
        assertEquals(2880, viewport.backBufferWidth());
        assertEquals(1620, viewport.backBufferHeight());
    }

    @Test
    void inputAndScissorsMapThroughLetterboxedViewport() {
        BattleUiViewport viewport = BattleUiViewport.fit(
            2560f, 1440f,
            1440, 900,
            2880, 1800);

        BattleUiViewport.LogicalPoint center = viewport.toLogical(720f, 450f);
        Rectangle scissor = viewport.scissor(new Rectangle(0f, 0f, 2560f, 144f));

        assertEquals(1280f, center.x(), 0.001f);
        assertEquals(720f, center.y(), 0.001f);
        assertEquals(0f, scissor.x, 0.001f);
        assertEquals(90f, scissor.y, 0.001f);
        assertEquals(2880f, scissor.width, 0.001f);
        assertEquals(162f, scissor.height, 0.001f);
        assertNull(viewport.toLogical(100f, 20f));
    }
}
