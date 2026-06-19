package com.jjktbf.graphics.ui.editor;

/**
 * Outcome of an editor Save attempt.
 *
 * Either ok (record persisted) or error with a human-readable message that the
 * base screen displays in its status bar. Created via {@link #ok()} and
 * {@link #error(String)}.
 */
public final class ValidationResult {

    private final boolean ok;
    private final String  message;

    private ValidationResult(boolean ok, String message) {
        this.ok      = ok;
        this.message = message;
    }

    /** Successful save. */
    public static ValidationResult ok() {
        return new ValidationResult(true, "Saved.");
    }

    /** Successful save with a custom confirmation message. */
    public static ValidationResult ok(String message) {
        return new ValidationResult(true, message);
    }

    /** Failed save — the message is shown to the user and nothing is persisted. */
    public static ValidationResult error(String message) {
        return new ValidationResult(false, message);
    }

    public boolean isOk()      { return ok; }
    public String  getMessage(){ return message; }
}
