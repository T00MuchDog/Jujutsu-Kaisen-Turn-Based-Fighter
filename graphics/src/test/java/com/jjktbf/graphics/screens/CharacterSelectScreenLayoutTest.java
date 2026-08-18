package com.jjktbf.graphics.screens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterSelectScreenLayoutTest {

    @Test
    void rosterScrollRevealsSelectionUsingExplicitRows() {
        float rowHeight = 66f;
        float viewportHeight = 470f;

        assertEquals(0f, CharacterSelectScreen.rosterScrollOffsetForSelection(
            0f, 0, 12, rowHeight, viewportHeight));
        assertEquals(58f, CharacterSelectScreen.rosterScrollOffsetForSelection(
            0f, 7, 12, rowHeight, viewportHeight));
        assertEquals(322f, CharacterSelectScreen.rosterScrollOffsetForSelection(
            58f, 11, 12, rowHeight, viewportHeight));
        assertEquals(0f, CharacterSelectScreen.rosterScrollOffsetForSelection(
            322f, 0, 12, rowHeight, viewportHeight));
    }
}
