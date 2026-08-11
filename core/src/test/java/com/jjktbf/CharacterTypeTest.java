package com.jjktbf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 coverage for the character-definition model: the SHIKIGAMI type,
 * the stored type + directlySelectable fields, subclass routing, and legacy
 * JSON defaulting.
 */
class CharacterTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyCharacterJsonDefaultsToSelectableSorcerer() throws Exception {
        // No "type" or "directlySelectable" fields — legacy shape.
        String json = """
            {
              "id": "000000",
              "name": "Legacy Sorcerer",
              "moveIds": []
            }
            """;
        CharacterData data = mapper.readValue(json, CharacterData.class);

        assertEquals(CharacterType.SORCERER, data.effectiveType());
        assertTrue(data.effectiveSelectable(),
            "legacy sorcerers must default to directly selectable");
    }

    @Test
    void unknownStoredTypeFailsLoudly() throws Exception {
        String json = """
            { "id": "000000", "name": "X", "type": "CURSED_SPIRIT", "moveIds": [] }
            """;
        CharacterData data = mapper.readValue(json, CharacterData.class);
        assertThrows(IllegalArgumentException.class, data::effectiveType);
    }

    @Test
    void shikigamiTypeRoundTripsThroughJson() throws Exception {
        CharacterData data = new CharacterData();
        data.id = "000010";
        data.name = "Divine Dog";
        data.type = CharacterType.SHIKIGAMI.name();
        data.baseCeDrainPerTick = 0.2;
        data.moveIds = java.util.List.of();

        String json = mapper.writeValueAsString(data);
        CharacterData roundTrip = mapper.readValue(json, CharacterData.class);

        assertEquals(CharacterType.SHIKIGAMI, roundTrip.effectiveType());
        assertEquals(0.2, roundTrip.baseCeDrainPerTick);
        assertFalse(roundTrip.effectiveSelectable(),
            "shikigami must default to NOT directly selectable");
    }

    @Test
    void shikigamiCanBeExplicitlyMarkedSelectable() throws Exception {
        CharacterData data = new CharacterData();
        data.id = "000011";
        data.name = "Selectable Shikigami";
        data.type = CharacterType.SHIKIGAMI.name();
        data.directlySelectable = Boolean.TRUE;
        data.moveIds = java.util.List.of();

        assertTrue(data.effectiveSelectable());
    }

    @Test
    void shikigamiSelectableOverrideRoundTrips() throws Exception {
        CharacterData data = new CharacterData();
        data.id = "000012";
        data.name = "Override Shikigami";
        data.type = CharacterType.SHIKIGAMI.name();
        data.directlySelectable = Boolean.TRUE;
        data.moveIds = java.util.List.of();

        String json = mapper.writeValueAsString(data);
        CharacterData roundTrip = mapper.readValue(json, CharacterData.class);
        assertTrue(roundTrip.effectiveSelectable());
        assertEquals(CharacterType.SHIKIGAMI, roundTrip.effectiveType());
    }

    @Test
    void constructTypedCharacterBuildsCorrectSubclass() {
        CharacterData sorcerer = new CharacterData();
        sorcerer.id = "000001";
        sorcerer.name = "S";
        sorcerer.moveIds = java.util.List.of();
        Character sorcererChar = sorcerer.constructTypedCharacter(
            sorcerer.toCharacterStats(), java.util.List.of(), java.util.List.of());
        assertInstanceOf(SorcererCharacter.class, sorcererChar);
        assertEquals(CharacterType.SORCERER, sorcererChar.getType());

        CharacterData shikigami = new CharacterData();
        shikigami.id = "000002";
        shikigami.name = "D";
        shikigami.type = CharacterType.SHIKIGAMI.name();
        shikigami.baseCeDrainPerTick = 0.25;
        shikigami.moveIds = java.util.List.of();
        Character shikigamiChar = shikigami.constructTypedCharacter(
            shikigami.toCharacterStats(), java.util.List.of(), java.util.List.of());
        assertInstanceOf(ShikigamiCharacter.class, shikigamiChar);
        assertEquals(CharacterType.SHIKIGAMI, shikigamiChar.getType());
        assertEquals(0.25, shikigamiChar.getBaseCeDrainPerTick());
    }

    @Test
    void fromCharacterPreservesType() {
        CharacterData sorcererData = new CharacterData();
        sorcererData.id = "000003";
        sorcererData.name = "S";
        sorcererData.moveIds = java.util.List.of();
        Character sorcererChar = sorcererData.constructTypedCharacter(
            sorcererData.toCharacterStats(), java.util.List.of(), java.util.List.of());
        CharacterData back = CharacterData.fromCharacter(sorcererChar);
        assertEquals(CharacterType.SORCERER, back.effectiveType());

        CharacterData shikigamiData = new CharacterData();
        shikigamiData.id = "000004";
        shikigamiData.name = "D";
        shikigamiData.type = CharacterType.SHIKIGAMI.name();
        shikigamiData.baseCeDrainPerTick = 0.35;
        shikigamiData.moveIds = java.util.List.of();
        Character shikigamiChar = shikigamiData.constructTypedCharacter(
            shikigamiData.toCharacterStats(), java.util.List.of(), java.util.List.of());
        CharacterData back2 = CharacterData.fromCharacter(shikigamiChar);
        assertEquals(CharacterType.SHIKIGAMI, back2.effectiveType());
        assertEquals(0.35, back2.baseCeDrainPerTick);
    }

    @Test
    void shikigamiRequiresPositiveBaseCeDrain() {
        CharacterData shikigami = new CharacterData();
        shikigami.id = "000006";
        shikigami.name = "D";
        shikigami.type = CharacterType.SHIKIGAMI.name();
        shikigami.moveIds = java.util.List.of();

        assertThrows(IllegalArgumentException.class, () -> shikigami.constructTypedCharacter(
            shikigami.toCharacterStats(), java.util.List.of(), java.util.List.of()));

        shikigami.baseCeDrainPerTick = 0.0;
        assertThrows(IllegalArgumentException.class, () -> shikigami.constructTypedCharacter(
            shikigami.toCharacterStats(), java.util.List.of(), java.util.List.of()));
    }

    @Test
    void titleRoundTripsThroughToCharacterAndFromCharacter() {
        CharacterData data = new CharacterData();
        data.id = "000005";
        data.name = "Satoru";
        data.title = "The Honored One";
        data.moveIds = java.util.List.of();

        Character character = data.toCharacter(null);
        assertTrue(character.hasTitle());
        assertEquals("The Honored One", character.getTitle());

        // fromCharacter preserves the title back into the DTO.
        CharacterData restored = CharacterData.fromCharacter(character);
        assertEquals("The Honored One", restored.title);
    }

    @Test
    void legacyCharacterJsonWithoutTitleLeavesTitleNull() throws Exception {
        String json = """
            {
              "id": "000000",
              "name": "Legacy Sorcerer",
              "moveIds": []
            }
            """;
        CharacterData data = mapper.readValue(json, CharacterData.class);
        assertEquals(null, data.title,
            "absent title on legacy saves resolves to null (no title)");
    }
}
