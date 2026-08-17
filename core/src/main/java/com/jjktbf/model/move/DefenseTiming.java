package com.jjktbf.model.move;

/**
 * When an active defence's window opens relative to its fire tick.
 *
 * <ul>
 *   <li>{@link #FIXED} — the historical behaviour: the window opens at the
 *       segment's fire tick and runs for the authored {@code blockDuration}.</li>
 *   <li>{@link #REACTION} — at its fire tick the defence does not open a
 *       window; it becomes <b>armed</b> until round end. The moment a hostile
 *       move is about to resolve against the wielder, the armed defence
 *       triggers and its window opens at that tick for the authored duration,
 *       contesting the triggering attack (satisfying the
 *       {@code requireFiredDefense} gate) and any later impacts its window
 *       covers. One trigger per placement; if no attack comes, the defence
 *       whiffs. A reaction trades the AP/CE premium for timing certainty —
 *       it predicts <i>that</i> an attack comes, not exactly when, so a
 *       triggered reaction never counts as a perfect read.</li>
 * </ul>
 */
public enum DefenseTiming {
    FIXED,
    REACTION;

    /** Resolve a stored enum name, tolerating null/blank/unknown as FIXED. */
    public static DefenseTiming fromName(String name) {
        if (name == null || name.isBlank()) return FIXED;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return FIXED;
        }
    }
}
