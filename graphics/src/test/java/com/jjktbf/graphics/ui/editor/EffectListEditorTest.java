package com.jjktbf.graphics.ui.editor;

import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectListEditorTest {

    @Test
    void summonSelectorOnlyRecognizesShikigamiDefinitions() {
        CharacterData sorcerer = character("000001", null);
        CharacterData shikigami = character("000002", CharacterType.SHIKIGAMI.name());

        assertFalse(EffectListEditor.isShikigamiReference(
            List.of(sorcerer, shikigami), "000001"));
        assertTrue(EffectListEditor.isShikigamiReference(
            List.of(sorcerer, shikigami), "000002"));
        assertFalse(EffectListEditor.isShikigamiReference(
            List.of(sorcerer, shikigami), "missing"));
    }

    @Test
    void transformationSelectorRecognizesEveryCharacterDefinition() {
        CharacterData sorcerer = character("000001", null);
        CharacterData shikigami = character("000002", CharacterType.SHIKIGAMI.name());

        assertTrue(EffectListEditor.isCharacterReference(
            List.of(sorcerer, shikigami), "000001"));
        assertTrue(EffectListEditor.isCharacterReference(
            List.of(sorcerer, shikigami), "000002"));
        assertFalse(EffectListEditor.isCharacterReference(
            List.of(sorcerer, shikigami), "missing"));
    }

    private static CharacterData character(String id, String type) {
        CharacterData character = new CharacterData();
        character.id = id;
        character.type = type;
        return character;
    }
}
