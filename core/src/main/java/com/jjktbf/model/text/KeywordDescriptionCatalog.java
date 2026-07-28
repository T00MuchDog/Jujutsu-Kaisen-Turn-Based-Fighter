package com.jjktbf.model.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.AppPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Loads tooltip copy and locates glossary keywords in player-facing text. */
public final class KeywordDescriptionCatalog {

    public static final String FILE_NAME = "keyword_descriptions.json";
    public static final String BUNDLED_RESOURCE = "data/" + FILE_NAME;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile KeywordDescriptionCatalog defaultCatalog;

    private final List<Entry> entries;

    public KeywordDescriptionCatalog(List<Entry> entries) {
        List<Entry> normalized = new ArrayList<>();
        Set<String> terms = new HashSet<>();
        for (Entry candidate : entries == null ? List.<Entry>of() : entries) {
            if (candidate == null || candidate.term() == null || candidate.term().isBlank()
                || candidate.description() == null || candidate.description().isBlank()) {
                throw new IllegalArgumentException("Keyword entries require a term and description");
            }
            Entry entry = new Entry(candidate.term().trim(), candidate.description().trim());
            if (!terms.add(entry.term().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate keyword term: " + entry.term());
            }
            normalized.add(entry);
        }
        normalized.sort(Comparator.comparingInt((Entry entry) -> entry.term().length()).reversed());
        this.entries = List.copyOf(normalized);
    }

    /** Returns the app-data catalog, falling back to the bundled copy. */
    public static KeywordDescriptionCatalog getDefault() {
        KeywordDescriptionCatalog result = defaultCatalog;
        if (result != null) return result;
        synchronized (KeywordDescriptionCatalog.class) {
            if (defaultCatalog == null) defaultCatalog = loadDefault();
            return defaultCatalog;
        }
    }

    public static KeywordDescriptionCatalog load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    public static KeywordDescriptionCatalog load(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<Entry> entries = MAPPER.readValue(input, new TypeReference<>() { });
        return new KeywordDescriptionCatalog(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Finds non-overlapping terms, preferring the longest term at each character. */
    public List<Match> findMatches(String text) {
        if (text == null || text.isEmpty() || entries.isEmpty()) return List.of();
        List<Match> matches = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            Entry matched = null;
            for (Entry entry : entries) {
                if (matchesAt(text, index, entry.term())) {
                    matched = entry;
                    break;
                }
            }
            if (matched == null) {
                index++;
                continue;
            }
            int end = index + matched.term().length();
            matches.add(new Match(index, end, matched));
            index = end;
        }
        return List.copyOf(matches);
    }

    private static KeywordDescriptionCatalog loadDefault() {
        Path dataFile = AppPaths.resolve("data").toPath().resolve(FILE_NAME);
        if (Files.isRegularFile(dataFile)) {
            try {
                return load(dataFile);
            } catch (IOException | IllegalArgumentException failure) {
                System.err.println("Warning: could not load keyword descriptions from " + dataFile
                    + ": " + failure.getMessage());
            }
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = KeywordDescriptionCatalog.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (input != null) return load(input);
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Warning: could not load bundled keyword descriptions: "
                + failure.getMessage());
        }
        return new KeywordDescriptionCatalog(List.of());
    }

    private static boolean matchesAt(String text, int start, String term) {
        int end = start + term.length();
        if (end > text.length() || !text.regionMatches(true, start, term, 0, term.length())) {
            return false;
        }
        if (isWordCharacter(term.charAt(0)) && start > 0
            && isWordCharacter(text.charAt(start - 1))) {
            return false;
        }
        return !isWordCharacter(term.charAt(term.length() - 1))
            || end >= text.length()
            || !isWordCharacter(text.charAt(end));
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    public record Entry(String term, String description) { }

    public record Match(int start, int end, Entry entry) {
        public Match {
            Objects.requireNonNull(entry, "entry");
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid keyword range");
        }
    }
}
