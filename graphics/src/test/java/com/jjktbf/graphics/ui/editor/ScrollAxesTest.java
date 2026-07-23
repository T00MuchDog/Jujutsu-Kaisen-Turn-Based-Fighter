package com.jjktbf.graphics.ui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScrollAxesTest {

    @Test
    void pureHorizontalSwipeKeepsOnlyX() {
        // From a real trackpad capture: intended horizontal swipe with a small
        // vertical leak (x=-4.2, y=0.8).
        float[] result = ScrollAxes.dominant(-4.2f, 0.8f);
        assertEquals(-4.2f, result[0], 0.0001f);
        assertEquals(0f, result[1], 0.0001f);
    }

    @Test
    void pureVerticalSwipeKeepsOnlyY() {
        // From a real trackpad capture: intended vertical swipe (x=-0.0, y=-8.1).
        float[] result = ScrollAxes.dominant(-0.0f, -8.1f);
        assertEquals(0f, result[0], 0.0001f);
        assertEquals(-8.1f, result[1], 0.0001f);
    }

    @Test
    void zeroMinorAxisPassesThroughUnchanged() {
        float[] result = ScrollAxes.dominant(0f, 3.0f);
        assertEquals(0f, result[0], 0.0001f);
        assertEquals(3.0f, result[1], 0.0001f);
    }

    @Test
    void ambiguousDiagonalPassesBothThrough() {
        // A genuinely diagonal gesture (equal magnitudes) is left alone so an oblique
        // swipe still feels responsive instead of dead.
        float[] result = ScrollAxes.dominant(2.0f, 2.0f);
        assertEquals(2.0f, result[0], 0.0001f);
        assertEquals(2.0f, result[1], 0.0001f);
    }
}
