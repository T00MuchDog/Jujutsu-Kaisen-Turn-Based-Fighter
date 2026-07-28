package com.jjktbf.graphics.ui.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordPopupPositionTest {

    @Test
    void prefersBottomLeftOfPopupAtTopRightOfWord() {
        KeywordPopupPosition.Position position = KeywordPopupPosition.place(
            20f, 30f, 40f, 10f, 100f, 60f, 300f, 200f);

        assertEquals(60f, position.x());
        assertEquals(40f, position.y());
    }

    @Test
    void flipsLeftAndBelowNearViewportEdges() {
        KeywordPopupPosition.Position position = KeywordPopupPosition.place(
            260f, 170f, 20f, 10f, 100f, 60f, 300f, 200f);

        assertEquals(160f, position.x());
        assertEquals(110f, position.y());
    }

    @Test
    void clampsOversizedPopupToViewportOrigin() {
        KeywordPopupPosition.Position position = KeywordPopupPosition.place(
            10f, 10f, 5f, 5f, 400f, 300f, 300f, 200f);

        assertEquals(0f, position.x());
        assertEquals(0f, position.y());
    }
}
