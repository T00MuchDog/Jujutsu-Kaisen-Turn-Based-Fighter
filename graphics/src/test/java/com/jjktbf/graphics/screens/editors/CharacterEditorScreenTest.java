package com.jjktbf.graphics.screens.editors;

import com.jjktbf.graphics.ui.editor.AssignmentPanel;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MovePool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void directSummonReferencesAreFoundBeforeCharacterDeletion() {
        MoveData move = move();
        move.summonCharacterId = "000002";
        AbilityEffectData effect = new AbilityEffectData();
        effect.characterId = "000002";
        AbilityData ability = new AbilityData();
        ability.effects = List.of(effect);

        assertSame(move, CharacterEditorScreen.firstMoveSummoningCharacter(
            List.of(move), "000002"));
        assertSame(ability, CharacterEditorScreen.firstAbilitySummoningCharacter(
            List.of(ability), "000002"));
    }

    @Test
    void characterResequencingRemapsMoveAndAbilitySummonReferences() {
        MoveData move = move();
        move.summonCharacterId = "000003";
        AbilityEffectData effect = new AbilityEffectData();
        effect.characterId = "000003";
        AbilityData ability = new AbilityData();
        ability.effects = List.of(effect);
        Map<String, String> remapped = Map.of("000003", "000002");

        assertTrue(CharacterEditorScreen.remapMoveSummonReferences(
            List.of(move), remapped));
        assertTrue(CharacterEditorScreen.remapAbilitySummonReferences(
            List.of(ability), remapped));
        assertEquals("000002", move.summonCharacterId);
        assertEquals("000002", effect.characterId);
    }

    private static MoveData move() {
        MoveData move = new MoveData();
        move.id = "000001";
        move.name = "Test Move";
        return move;
    }
}
