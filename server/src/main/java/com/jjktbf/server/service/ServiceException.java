package com.jjktbf.server.service;

import com.jjktbf.multiplayer.protocol.ErrorResponse;

import java.util.Map;
import java.util.Objects;

/** Safe, structured failure intended to cross the future transport boundary. */
public final class ServiceException extends RuntimeException {
    private final ServiceErrorCode errorCode;
    private final Map<String, String> details;

    public ServiceException(ServiceErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public ServiceException(
        ServiceErrorCode errorCode,
        String message,
        Map<String, String> details
    ) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return errorCode.name();
    }

    public String getCode() {
        return code();
    }

    public ServiceErrorCode errorCode() {
        return errorCode;
    }

    public int suggestedStatus() {
        return errorCode.suggestedStatus();
    }

    public int getSuggestedStatus() {
        return suggestedStatus();
    }

    public Map<String, String> details() {
        return details;
    }

    public ErrorResponse toResponse() {
        return new ErrorResponse(code(), getMessage(), details);
    }
}
