package com.jjktbf.server.service;

/** Stable application error codes and their suggested HTTP statuses. */
public enum ServiceErrorCode {
    INVALID_TOKEN(401),
    MALFORMED_REQUEST(400),
    INVALID_DISPLAY_NAME(400),
    DISPLAY_NAME_TAKEN(409),
    INCOMPATIBLE_VERSION(409),
    INVALID_CHARACTER(400),
    CHALLENGE_NOT_FOUND(404),
    CHALLENGE_NOT_OPEN(409),
    CHALLENGE_ALREADY_ACCEPTED(409),
    CHALLENGE_CANCELLED(409),
    CHALLENGE_EXPIRED(409),
    CANNOT_ACCEPT_OWN_CHALLENGE(409),
    MATCH_NOT_FOUND(404),
    PLAYER_NOT_IN_MATCH(403),
    MATCH_NOT_READY(409),
    FORBIDDEN(403),
    TOO_MANY_OPEN_CHALLENGES(429),
    INTERNAL_ERROR(500);

    private final int suggestedStatus;

    ServiceErrorCode(int suggestedStatus) {
        this.suggestedStatus = suggestedStatus;
    }

    public int suggestedStatus() {
        return suggestedStatus;
    }
}
