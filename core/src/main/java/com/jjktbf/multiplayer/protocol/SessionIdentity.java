package com.jjktbf.multiplayer.protocol;

/** Public identity associated with an authenticated guest session. */
public record SessionIdentity(
    String playerId,
    String displayName,
    long expiresAt
) {
}
