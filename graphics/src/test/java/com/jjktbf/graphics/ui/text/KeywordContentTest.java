package com.jjktbf.graphics.ui.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.text.KeywordDescriptionCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordContentTest {

    private static final Pattern UPPERCASE_TERM = Pattern.compile("[A-Z][A-Z_]+");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyShippedMechanicalDescriptionIsShortAndContainsAKeyword() throws Exception {
        KeywordDescriptionCatalog catalog;
        try (InputStream input = resource(KeywordDescriptionCatalog.BUNDLED_RESOURCE)) {
            catalog = KeywordDescriptionCatalog.load(input);
        }

        try (InputStream input = resource("data/moves/all_moves.json")) {
            List<Map<String, Object>> moves = mapper.readValue(input, new TypeReference<>() { });
            for (Map<String, Object> move : moves) {
                assertMechanicalSentence(catalog, String.valueOf(move.get("name")),
                    String.valueOf(move.get("description")));
            }
        }

        try (InputStream input = resource("data/abilities/all_abilities.json")) {
            List<Map<String, Object>> abilities = mapper.readValue(input, new TypeReference<>() { });
            for (Map<String, Object> ability : abilities) {
                assertMechanicalSentence(catalog, String.valueOf(ability.get("name")),
                    String.valueOf(ability.get("mechanicText")));
            }
        }
    }

    private static InputStream resource(String path) {
        InputStream input = KeywordContentTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(input, "Missing test resource " + path);
        return input;
    }

    private static void assertMechanicalSentence(
        KeywordDescriptionCatalog catalog,
        String name,
        String text
    ) {
        assertTrue(text.endsWith("."), name + " must be a sentence");
        assertTrue(text.length() <= 180, name + " must remain short");
        List<KeywordDescriptionCatalog.Match> matches = catalog.findMatches(text);
        assertFalse(matches.isEmpty(), name + " must contain a keyword");
        Matcher uppercaseTerms = UPPERCASE_TERM.matcher(text);
        while (uppercaseTerms.find()) {
            boolean covered = matches.stream().anyMatch(match ->
                match.start() <= uppercaseTerms.start() && match.end() >= uppercaseTerms.end());
            assertTrue(covered, name + " has no keyword entry for " + uppercaseTerms.group());
        }
    }
}
