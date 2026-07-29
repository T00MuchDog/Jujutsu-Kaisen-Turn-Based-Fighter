package com.jjktbf.server.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentCatalogTest {
    @Test
    void loadsCanonicalClasspathDefinitionsIntoDomainCharacters() {
        ContentCatalog catalog = ContentCatalog.load();

        assertEquals(7, catalog.characterSummaries().size());
        assertTrue(catalog.findCharacter("000000").isPresent());
        assertTrue(catalog.findCharacter("000005").orElseThrow().getKnownMoves().stream()
            .anyMatch(move -> "Simple Domain".equals(move.getName())));
        assertTrue(catalog.findCharacter("000003").orElseThrow().hasWeapon());
        assertTrue(catalog.findCharacter("000003").orElseThrow().getKnownMoves().stream()
            .anyMatch(move -> "Sword Slash".equals(move.getName())));
        assertFalse(catalog.findCharacter("missing").isPresent());
        assertFalse(catalog.findCharacter("000000").orElseThrow().getKnownMoves().isEmpty());
        var yuji = catalog.findCharacter("000006").orElseThrow();
        assertEquals(15, yuji.getBaseStats().getJujutsuSkill());
        assertEquals(95, yuji.getBaseStats().getCombatAbility());
        assertFalse(yuji.getKnownMoves().stream()
            .anyMatch(move -> move.getName().startsWith("Reinforced ")));
        var divergentFist = yuji.getKnownMoves().stream()
            .filter(move -> "Divergent Fist".equals(move.getName()))
            .findFirst().orElseThrow();
        assertEquals(32, divergentFist.getBasePower());
        assertEquals(2, divergentFist.getHitComponents().size());
        assertEquals(2, divergentFist.getHitComponents().get(1).getDelayTicks());
        assertFalse(divergentFist.isBlackFlashEligible());
        assertThrows(UnsupportedOperationException.class,
            () -> catalog.characterSummaries().clear());
    }
}
