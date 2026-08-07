package com.jjktbf.server.content;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 coverage for the generalized server content catalog: it now stores
 * abstract {@link Character} definitions (not just sorcerers), exposes only
 * directly-selectable definitions as summaries, and resolves hidden shikigami
 * only via {@code findCharacter} (for summons) — never via selectable lookup.
 */
class ContentCatalogTypeTest {

    @Test
    void ofAcceptsAbstractCharactersAndIndexesById() {
        SorcererCharacter sorcerer = new SorcererCharacter(
            "000001", "Yuji", base(), null, List.of(), List.of(), false);
        ContentCatalog catalog = ContentCatalog.of(List.of(sorcerer));

        assertEquals(1, catalog.characterSummaries().size());
        assertEquals("Yuji", catalog.characterSummaries().get(0).name());
        Character found = catalog.findCharacter("000001").orElseThrow();
        assertInstanceOf(SorcererCharacter.class, found);
    }

    @Test
    void selectableCharacterHidesShikigamiButFindCharacterStillResolvesIt() {
        SorcererCharacter sorcerer = new SorcererCharacter(
            "000001", "Sukuna", base(), null, List.of(), List.of(), false);
        ShikigamiCharacter shikigami = new ShikigamiCharacter(
            "000002", "Divine Dog", base(), null, List.of(), List.of(), false);
        ContentCatalog catalog = ContentCatalog.of(List.of(sorcerer, shikigami));

        // Both are resolvable for summon construction...
        assertTrue(catalog.findCharacter("000002").isPresent());
        // ...but only the selectable sorcerer appears in rosters/summaries.
        assertEquals(1, catalog.characterSummaries().size(),
            "shikigami must be hidden from character summaries");
        assertEquals("Sukuna", catalog.characterSummaries().get(0).name());

        // A crafted request naming the hidden shikigami as a primary fighter
        // is rejected by the selectable resolver.
        assertFalse(catalog.findSelectableCharacter("000002").isPresent());
        assertTrue(catalog.findSelectableCharacter("000001").isPresent());
    }

    @Test
    void storedSelectabilityOverridesWinOverTypeDefaults() {
        SorcererCharacter hiddenSorcerer = new SorcererCharacter(
            "000001", "Hidden", base(), null, List.of(), List.of(), false);
        ShikigamiCharacter selectableShikigami = new ShikigamiCharacter(
            "000002", "Selectable", base(), null, List.of(), List.of(), false);

        ContentCatalog catalog = ContentCatalog.of(
            List.of(hiddenSorcerer, selectableShikigami),
            Map.of("000001", false, "000002", true));

        assertEquals(List.of("000002"), catalog.characterSummaries().stream()
            .map(CharacterSummary::characterId).toList());
        assertTrue(catalog.findCharacter("000001").isPresent());
        assertFalse(catalog.findSelectableCharacter("000001").isPresent());
        assertTrue(catalog.findSelectableCharacter("000002").isPresent());
    }

    @Test
    void summonReferencesMustResolveToShikigamiDefinitions() {
        SorcererCharacter sorcerer = new SorcererCharacter(
            "000001", "Sorcerer", base(), null, List.of(), List.of(), false);
        ShikigamiCharacter shikigami = new ShikigamiCharacter(
            "000002", "Shikigami", base(), null, List.of(), List.of(), false);
        Map<String, Character> characters = Map.of(
            sorcerer.getId(), sorcerer, shikigami.getId(), shikigami);

        MoveData move = new MoveData();
        move.id = "000010";
        move.summonCharacterId = shikigami.getId();
        AbilityEffectData summon = AbilityEffectType.SUMMON_CHARACTER.createDefault();
        summon.characterId = shikigami.getId();
        AbilityData ability = new AbilityData();
        ability.id = "000020";
        ability.effects = List.of(summon);

        assertDoesNotThrow(() -> ContentCatalog.validateSummonReferences(
            List.of(move), List.of(ability), characters));

        move.summonCharacterId = sorcerer.getId();
        assertThrows(IllegalStateException.class,
            () -> ContentCatalog.validateSummonReferences(
                List.of(move), List.of(ability), characters));

        move.summonCharacterId = shikigami.getId();
        summon.characterId = "missing";
        assertThrows(IllegalStateException.class,
            () -> ContentCatalog.validateSummonReferences(
                List.of(move), List.of(ability), characters));
    }

    private static com.jjktbf.model.character.CharacterStats base() {
        return new com.jjktbf.model.character.CharacterStats.Builder().build();
    }

    @Test
    void effectiveTypeResolvesLegacySorcererDefault() {
        CharacterData legacy = new CharacterData();
        legacy.id = "000003";
        legacy.name = "Legacy";
        legacy.moveIds = List.of();
        assertEquals(CharacterType.SORCERER, legacy.effectiveType());
        assertTrue(legacy.effectiveSelectable());
    }
}
