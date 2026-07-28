package com.jjktbf.model.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeywordDescriptionCatalogTest {

    @Test
    void longestKeywordWinsAndMatchingIgnoresCase() {
        KeywordDescriptionCatalog catalog = new KeywordDescriptionCatalog(List.of(
            new KeywordDescriptionCatalog.Entry("CURSED ENERGY", "Resource"),
            new KeywordDescriptionCatalog.Entry("CURSED ENERGY OUTPUT", "Stat"),
            new KeywordDescriptionCatalog.Entry("RATIO", "Technique")
        ));

        List<KeywordDescriptionCatalog.Match> matches = catalog.findMatches(
            "Gain CURSED ENERGY OUTPUT, then apply ratio.");

        assertEquals(2, matches.size());
        assertEquals("CURSED ENERGY OUTPUT", matches.get(0).entry().term());
        assertEquals("RATIO", matches.get(1).entry().term());
    }

    @Test
    void keywordsDoNotMatchInsideLargerWords() {
        KeywordDescriptionCatalog catalog = new KeywordDescriptionCatalog(List.of(
            new KeywordDescriptionCatalog.Entry("RATIO", "Technique")
        ));

        assertEquals(0, catalog.findMatches("irrational").size());
        assertEquals(1, catalog.findMatches("Apply RATIO.").size());
    }

    @Test
    void termsMustBeUniqueIgnoringCase() {
        List<KeywordDescriptionCatalog.Entry> entries = List.of(
            new KeywordDescriptionCatalog.Entry("Miracles", "Technique"),
            new KeywordDescriptionCatalog.Entry("MIRACLES", "Resource")
        );

        assertThrows(IllegalArgumentException.class,
            () -> new KeywordDescriptionCatalog(entries));
    }
}
