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

        assertEquals(6, catalog.characterSummaries().size());
        assertTrue(catalog.findCharacter("000000").isPresent());
        assertTrue(catalog.findCharacter("000005").orElseThrow().getKnownMoves().stream()
            .anyMatch(move -> "Simple Domain".equals(move.getName())));
        assertFalse(catalog.findCharacter("missing").isPresent());
        assertFalse(catalog.findCharacter("000000").orElseThrow().getKnownMoves().isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> catalog.characterSummaries().clear());
    }
}
