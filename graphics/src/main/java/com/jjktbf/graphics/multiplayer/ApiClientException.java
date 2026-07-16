package com.jjktbf.graphics.multiplayer;

/** Safe, structured failure from the asynchronous HTTP API boundary. */
public final class ApiClientException extends RuntimeException {
    public enum Kind {
        HTTP_ERROR,
        UNAVAILABLE,
        TIMEOUT,
        MALFORMED_RESPONSE,
        CLIENT_CLOSED
    }

    private final Kind kind;
    private final int status;
    private final String code;
    private final String userMessage;

    public ApiClientException(
        Kind kind,
        int status,
        String code,
        String userMessage,
        Throwable cause
    ) {
        super(requireText(userMessage, "userMessage"), cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.status = status;
        this.code = requireText(code, "code");
        this.userMessage = userMessage;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status, or {@code -1} when no response was received. */
    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String userMessage() {
        return userMessage;
    }

    public boolean isInvalidToken() {
        return status == 401 || "INVALID_TOKEN".equals(code);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
