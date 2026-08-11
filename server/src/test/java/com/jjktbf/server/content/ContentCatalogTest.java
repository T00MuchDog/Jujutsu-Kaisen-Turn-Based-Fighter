package com.jjktbf.server.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentCatalogTest {
    @Test
    void loadsCanonicalClasspathDefinitions() {
        ContentCatalog catalog = ContentCatalog.load();

        assertFalse(catalog.findCharacter("missing").isPresent());
        assertThrows(UnsupportedOperationException.class,
            () -> catalog.characterSummaries().clear());
    }
}
