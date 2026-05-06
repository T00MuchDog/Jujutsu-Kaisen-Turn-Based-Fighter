package com.jjktbf.editor;

import java.util.Scanner;

/**
 * Shared I/O utilities for all editor CLI tools.
 *
 * Wraps a Scanner and provides consistent prompt, display, and
 * validation helpers so each editor does not duplicate these ~100 lines.
 *
 * All prompt methods print to stdout and read from the injected Scanner.
 * All display helpers write to stdout.
 */
public final class EditorIO {

    private final Scanner sc;

    public EditorIO(Scanner sc) {
        this.sc = sc;
    }

    // =========================================================================
    // Raw input
    // =========================================================================

    /** Print label (no newline) and return the raw input line. */
    public String prompt(String label) {
        System.out.print(label);
        return sc.nextLine();
    }

    /** Prompt with a displayed current value. Returns current if blank. */
    public String promptWithDefault(String label, String current) {
        String display = (current != null && !current.isBlank()) ? " [" + current + "]" : " [blank]";
        String input   = prompt("  " + label + display + ": ").trim();
        return input.isBlank() ? (current != null ? current : "") : input;
    }

    /** Prompt for a non-blank string, retrying until satisfied. */
    public String promptNonEmpty(String label, String current) {
        while (true) {
            String v = promptWithDefault(label, current);
            if (!v.isBlank()) return v;
            System.out.println("  Cannot be blank.");
        }
    }

    // =========================================================================
    // Numeric prompts
    // =========================================================================

    /** Prompt for an int in [min, max], keep current on blank. */
    public int promptInt(String label, int current, int min, int max) {
        while (true) {
            String input = prompt("  " + label + " [" + current + "]: ").trim();
            if (input.isBlank()) return current;
            try {
                int v = Integer.parseInt(input);
                if (v >= min && v <= max) return v;
                System.out.printf("  Must be between %d and %d.%n", min, max);
            } catch (NumberFormatException e) {
                System.out.println("  Enter a whole number.");
            }
        }
    }

    /** Prompt for an int >= min with no upper bound. Accepts "N/A"/"na" as 0. */
    public int promptIntUnbounded(String label, int current, int min) {
        while (true) {
            String input = prompt("  " + label + " [" + current + "]: ").trim();
            if (input.isBlank()) return current;
            if (input.equalsIgnoreCase("n/a") || input.equalsIgnoreCase("na")) return 0;
            try {
                int v = Integer.parseInt(input);
                if (v >= min) return v;
                System.out.printf("  Must be >= %d.%n", min);
            } catch (NumberFormatException e) {
                System.out.println("  Enter a whole number (or N/A).");
            }
        }
    }

    /** Prompt for any integer (no minimum enforced beyond negativity check for stats). */
    public int promptIntUnbounded(String label, int current) {
        return promptIntUnbounded(label, current, Integer.MIN_VALUE);
    }

    /** Prompt for a double, keep current on blank. */
    public double promptDouble(String label, double current) {
        while (true) {
            String input = prompt("  " + label + " [" + current + "]: ").trim();
            if (input.isBlank()) return current;
            try {
                return Double.parseDouble(input);
            } catch (NumberFormatException e) {
                System.out.println("  Enter a decimal number.");
            }
        }
    }

    /** Prompt for a valid enum name (case-insensitive). Returns the name string. */
    public <E extends Enum<E>> String promptEnum(String label, String current, Class<E> enumClass) {
        while (true) {
            String input = promptWithDefault(label, current).toUpperCase().replace(" ", "_");
            try {
                Enum.valueOf(enumClass, input);
                return input;
            } catch (IllegalArgumentException e) {
                System.out.println("  Invalid value. Choose from the listed options.");
            }
        }
    }

    // =========================================================================
    // Display helpers
    // =========================================================================

    /** Print a section separator with a title. */
    public void sep(String title) {
        System.out.println("  ─── " + title + " " + "─".repeat(Math.max(0, 50 - title.length())));
    }

    /** Print a single key-value field inside a card border. */
    public void printField(String label, Object value) {
        System.out.printf("  │  %-16s: %-39s│%n", label, truncate(String.valueOf(value), 39));
    }

    /** Print a text field that wraps long descriptions inside card borders. */
    public void printWrapped(String label, String text) {
        System.out.printf("  │  %-16s:%n", label);
        if (text == null || text.isBlank()) {
            System.out.printf("  │    %-53s│%n", "—");
            return;
        }
        while (text.length() > 53) {
            int cut = text.lastIndexOf(' ', 53);
            if (cut <= 0) cut = 53;
            System.out.printf("  │    %-53s│%n", text.substring(0, cut));
            text = text.substring(cut).trim();
        }
        if (!text.isEmpty()) System.out.printf("  │    %-53s│%n", text);
    }

    // =========================================================================
    // String utilities
    // =========================================================================

    /** Truncate a string to max chars, appending "…" if trimmed. */
    public static String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
