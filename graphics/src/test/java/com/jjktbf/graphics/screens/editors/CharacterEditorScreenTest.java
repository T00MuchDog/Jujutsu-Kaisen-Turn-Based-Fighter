package com.jjktbf.graphics.screens.editors;

import com.jjktbf.graphics.ui.editor.AssignmentPanel;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MovePool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterEditorScreenTest {

    @Test
    void fullPoolRetainsOtherwiseAvailableMoveAsLocked() {
        AssignmentPanel.Item item = CharacterEditorScreen.availableMoveItem(
            move(), "PHYSICAL", MovePool.COMBAT_ARTS,
            "No available COMBAT_ARTS slots");

        assertEquals("000001", item.id);
        assertTrue(item.locked);
        assertEquals("No available COMBAT_ARTS slots", item.lockReason);
    }

    @Test
    void otherEligibilityErrorsKeepMoveOutOfAvailableList() {
        AssignmentPanel.Item item = CharacterEditorScreen.availableMoveItem(
            move(), "PHYSICAL", MovePool.COMBAT_ARTS, "Needs Strength >= 100");

        assertNull(item);
    }

    @Test
    void eligibleMoveRemainsUnlocked() {
        AssignmentPanel.Item item = CharacterEditorScreen.availableMoveItem(
            move(), "PHYSICAL", MovePool.COMBAT_ARTS, null);

        assertFalse(item.locked);
    }

    @Test
    void characterRecordSectionsUseTheCanonicalBaseStatTier() {
        CharacterData character = new CharacterData();
        assertEquals("GRADE 2", CharacterEditorScreen.characterTierSection(character));

        for (StatKey stat : StatKey.values()) stat.set(character, 300);
        assertEquals("CALAMITY", CharacterEditorScreen.characterTierSection(character));
    }

    private static MoveData move() {
        MoveData move = new MoveData();
        move.id = "000001";
        move.name = "Test Move";
        return move;
    }
}
