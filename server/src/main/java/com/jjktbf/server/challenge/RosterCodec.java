package com.jjktbf.server.challenge;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes an ordered canonical-character roster to/from a single
 * comma-joined column value. Roster ids are the fixed-width canonical ids
 * (e.g. {@code "000004"}), which never contain a comma, so this simple scheme
 * is safe and round-trips order exactly. An empty/blank column decodes to an
 * empty list.
 */
final class RosterCodec {

    private RosterCodec() {
    }

    static String encode(List<String> roster) {
        if (roster == null || roster.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < roster.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            String id = roster.get(index);
            if (id == null) {
                throw new IllegalArgumentException("roster id cannot be null");
            }
            builder.append(id);
        }
        return builder.toString();
    }

    static List<String> decode(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",", -1);
        List<String> roster = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                    "roster value contains a blank id: " + value);
            }
            roster.add(trimmed);
        }
        return List.copyOf(roster);
    }

    static List<String> decodeOrEmpty(String value) {
        try {
            return decode(value);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }
}
