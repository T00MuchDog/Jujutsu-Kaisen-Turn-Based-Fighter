package com.jjktbf.graphics.ui.editor;

import com.jjktbf.model.text.KeywordDescriptionCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeywordAutocompleteTest {

    private static final List<KeywordDescriptionCatalog.Entry> ENTRIES = List.of(
        entry("CURSED ENERGY"),
        entry("CURSED ENERGY OUTPUT"),
        entry("GUARD BREAK"),
        entry("AP"),
        entry("AP TICK"),
        entry("Basic Strike")
    );

    @Test
    void uppercaseFragmentFiltersMechanicalKeywordsAlphabetically() {
        KeywordAutocomplete.Query query = KeywordAutocomplete.query("Gain C", 6, ENTRIES);

        assertEquals("C", query.fragment());
        assertEquals(List.of("CURSED ENERGY", "CURSED ENERGY OUTPUT"),
            query.matches().stream().map(KeywordDescriptionCatalog.Entry::term).toList());
    }

    @Test
    void lowercaseInputDoesNotTriggerButTitleCasedCatalogTermsAreSuggestedInCaps() {
        assertNull(KeywordAutocomplete.query("Gain c", 6, ENTRIES));

        KeywordAutocomplete.Query query = KeywordAutocomplete.query("Use B", 5, ENTRIES);
        assertEquals(List.of("BASIC STRIKE"), query.matches().stream()
            .map(KeywordAutocomplete::insertionText).toList());
    }

    @Test
    void longestMatchingPhraseIsReplaced() {
        String text = "Gain GUARD B now";
        KeywordAutocomplete.Query query = KeywordAutocomplete.query(text, 12, ENTRIES);

        assertEquals("GUARD B", query.fragment());
        assertEquals(List.of("GUARD BREAK"),
            query.matches().stream().map(KeywordDescriptionCatalog.Entry::term).toList());
        assertEquals("Gain GUARD BREAK now",
            KeywordAutocomplete.complete(text, query, "GUARD BREAK"));
    }

    @Test
    void unmatchedUppercaseProseFallsBackToCurrentWord() {
        KeywordAutocomplete.Query query = KeywordAutocomplete.query("GAIN C", 6, ENTRIES);

        assertEquals(5, query.start());
        assertEquals("C", query.fragment());
    }

    @Test
    void completionReplacesTheRestOfAWordAfterTheCaret() {
        String text = "Gain GUARD BREK";
        KeywordAutocomplete.Query query = KeywordAutocomplete.query(text, 14, ENTRIES);

        assertEquals("Gain GUARD BREAK",
            KeywordAutocomplete.complete(text, query, "GUARD BREAK"));
    }

    @Test
    void completionDoesNotPartiallyConsumeAnUnrelatedSuffix() {
        String text = "AP TIME";
        KeywordAutocomplete.Query query = KeywordAutocomplete.query(text, 2, ENTRIES);

        assertEquals("AP TICK TIME",
            KeywordAutocomplete.complete(text, query, "AP TICK"));
    }

    @Test
    void completionConsumesAnExistingMatchingPartialSuffix() {
        String text = "Gain CURSED EN";
        KeywordAutocomplete.Query query = KeywordAutocomplete.query(text, 11, ENTRIES);

        assertEquals("Gain CURSED ENERGY",
            KeywordAutocomplete.complete(text, query, "CURSED ENERGY"));
    }

    @Test
    void completionPreservesTheSeparatorBeforeLowercaseProse() {
        String text = "AP time";
        KeywordAutocomplete.Query query = KeywordAutocomplete.query(text, 2, ENTRIES);

        assertEquals("AP TICK time",
            KeywordAutocomplete.complete(text, query, "AP TICK"));
    }

    @Test
    void completionPreservesTheSeparatorBeforeTitleCasedProse() {
        String text = "AP Time";
        KeywordAutocomplete.Query query = KeywordAutocomplete.query(text, 2, ENTRIES);

        assertEquals("AP TICK Time",
            KeywordAutocomplete.complete(text, query, "AP TICK"));
    }

    @Test
    void trailingSpaceClosesSuggestionsUntilTheNextWordStarts() {
        assertNull(KeywordAutocomplete.query("Use GUARD ", 10, ENTRIES));
    }

    private static KeywordDescriptionCatalog.Entry entry(String term) {
        return new KeywordDescriptionCatalog.Entry(term, term + " description");
    }
}
