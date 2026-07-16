package com.jjktbf.server.db;

/** Unchecked boundary for infrastructure-level database failures. */
public final class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
