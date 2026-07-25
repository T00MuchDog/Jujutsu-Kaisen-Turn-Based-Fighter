package com.jjktbf.graphics.ui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveAssignmentPanelTest {

    private final AssignmentPanel.Item item = new AssignmentPanel.Item(
        "000025", "Ratio Mark", "UTILITY, INNATE_TECHNIQUE, CURSED_ENERGY");

    @Test
    void searchMatchesNameTagsAndIdentifierWithoutCaseSensitivity() {
        assertTrue(MoveAssignmentPanel.matchesSearch(item, "ratio"));
        assertTrue(MoveAssignmentPanel.matchesSearch(item, "utility"));
        assertTrue(MoveAssignmentPanel.matchesSearch(item, "000025"));
        assertTrue(MoveAssignmentPanel.matchesSearch(item, "  CuRsEd  "));
        assertFalse(MoveAssignmentPanel.matchesSearch(item, "physical"));
    }

    @Test
    void blankSearchShowsEveryMove() {
        assertTrue(MoveAssignmentPanel.matchesSearch(item, ""));
        assertTrue(MoveAssignmentPanel.matchesSearch(item, "   "));
        assertTrue(MoveAssignmentPanel.matchesSearch(item, null));
    }
}
