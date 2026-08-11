package com.jjktbf.graphics.ui.editor;

import com.jjktbf.model.text.KeywordDescriptionCatalog;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure matching and replacement logic for keyword description autocomplete. */
final class KeywordAutocomplete {

    private static final Comparator<KeywordDescriptionCatalog.Entry> TERM_ORDER =
        Comparator.comparing(KeywordDescriptionCatalog.Entry::term,
            String.CASE_INSENSITIVE_ORDER);

    private KeywordAutocomplete() { }

    static Query query(
        String text,
        int cursor,
        List<KeywordDescriptionCatalog.Entry> catalogEntries
    ) {
        String value = text == null ? "" : text;
        int caret = Math.max(0, Math.min(cursor, value.length()));
        if (caret == 0 || !isUppercaseWordCharacter(value.charAt(caret - 1))) return null;

        int runStart = caret - 1;
        while (runStart > 0 && isUppercaseFragmentCharacter(value.charAt(runStart - 1))) {
            runStart--;
        }
        while (runStart < caret && value.charAt(runStart) == ' ') runStart++;

        List<KeywordDescriptionCatalog.Entry> entries = catalogEntries == null
            ? List.of()
            : catalogEntries;
        for (int candidateStart = runStart; candidateStart < caret;) {
            String fragment = value.substring(candidateStart, caret);
            List<KeywordDescriptionCatalog.Entry> matches = entries.stream()
                .filter(entry -> entry != null && entry.term() != null && !entry.term().isBlank())
                .filter(entry -> startsWithIgnoreCase(entry.term(), fragment))
                .sorted(TERM_ORDER)
                .toList();
            if (!matches.isEmpty()) {
                int replacementEnd = caret;
                while (replacementEnd < value.length()
                    && isUppercaseWordCharacter(value.charAt(replacementEnd))) {
                    replacementEnd++;
                }
                return new Query(candidateStart, replacementEnd, fragment, matches);
            }

            int nextWord = value.indexOf(' ', candidateStart);
            if (nextWord < 0 || nextWord >= caret) break;
            candidateStart = nextWord + 1;
            while (candidateStart < caret && value.charAt(candidateStart) == ' ') {
                candidateStart++;
            }
        }
        return null;
    }

    static String complete(String text, Query query, String term) {
        if (query == null || term == null) return text == null ? "" : text;
        String value = text == null ? "" : text;
        int start = Math.max(0, Math.min(query.start(), value.length()));
        int end = Math.max(start, Math.min(query.end(), value.length()));
        int typedLength = end - start;
        if (typedLength <= term.length()
            && value.regionMatches(true, start, term, 0, typedLength)) {
            int continuationEnd = end;
            while (continuationEnd < value.length()
                && isUppercaseFragmentCharacter(value.charAt(continuationEnd))) {
                continuationEnd++;
            }
            boolean hasContinuationWord = false;
            for (int index = end; index < continuationEnd; index++) {
                if (isUppercaseWordCharacter(value.charAt(index))) {
                    hasContinuationWord = true;
                    break;
                }
            }
            int continuationLength = Math.min(
                continuationEnd - end,
                term.length() - typedLength);
            int matchedEnd = end + continuationLength;
            if (hasContinuationWord && continuationLength > 0
                && value.regionMatches(true, end, term, typedLength, continuationLength)
                && (matchedEnd >= value.length()
                    || !isWordCharacter(value.charAt(matchedEnd)))) {
                end = matchedEnd;
            }
        }
        return value.substring(0, start) + term + value.substring(end);
    }

    static String insertionText(KeywordDescriptionCatalog.Entry entry) {
        return entry.term().toUpperCase(Locale.ROOT);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length()
            && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isUppercaseFragmentCharacter(char character) {
        return character == ' '
            || isUppercaseWordCharacter(character);
    }

    private static boolean isUppercaseWordCharacter(char character) {
        return Character.isUpperCase(character)
            || Character.isDigit(character)
            || character == '_'
            || character == '-';
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character)
            || character == '_'
            || character == '-';
    }

    record Query(
        int start,
        int end,
        String fragment,
        List<KeywordDescriptionCatalog.Entry> matches
    ) {
        Query {
            matches = List.copyOf(matches);
        }
    }
}
