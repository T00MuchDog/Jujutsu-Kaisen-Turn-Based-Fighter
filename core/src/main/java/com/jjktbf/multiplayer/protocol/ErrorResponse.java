package com.jjktbf.multiplayer.protocol;

import java.util.Map;

/** Stable machine-readable error with optional structured details. */
public record ErrorResponse(
    String code,
    String message,
    Map<String, String> details
) {
    public ErrorResponse {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Map.of());
    }
}
