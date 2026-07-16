package com.jjktbf.server.auth;

record GuestSessionRecord(
    String sessionId,
    String playerId,
    String displayName,
    String tokenHash,
    long expiresAt,
    Long revokedAt
) {
}
