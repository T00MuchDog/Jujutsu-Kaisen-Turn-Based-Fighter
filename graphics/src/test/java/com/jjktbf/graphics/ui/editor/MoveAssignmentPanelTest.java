package com.jjktbf.graphics.ui.editor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void draggedMoveCanBePlacedBeforeAnotherMove() {
        List<String> ids = new ArrayList<>(List.of("a", "b", "c", "d"));

        assertTrue(MoveAssignmentPanel.moveToInsertionIndex(ids, 3, 1));

        assertEquals(List.of("a", "d", "b", "c"), ids);
    }

    @Test
    void droppingAfterEveryMovePlacesDraggedMoveAtTheBottom() {
        List<String> ids = new ArrayList<>(List.of("a", "b", "c"));

        assertTrue(MoveAssignmentPanel.moveToInsertionIndex(ids, 0, 3));

        assertEquals(List.of("b", "c", "a"), ids);
    }

    @Test
    void droppingMoveInItsCurrentPositionDoesNotReportAChange() {
        List<String> ids = new ArrayList<>(List.of("a", "b", "c"));

        assertFalse(MoveAssignmentPanel.moveToInsertionIndex(ids, 1, 2));

        assertEquals(List.of("a", "b", "c"), ids);
    }

    @Test
    void duplicateMoveIdsAreReorderedByExactPosition() {
        List<String> ids = new ArrayList<>(List.of("a", "b", "a"));

        assertTrue(MoveAssignmentPanel.moveToInsertionIndex(ids, 2, 1));

        assertEquals(List.of("a", "a", "b"), ids);
    }
}
