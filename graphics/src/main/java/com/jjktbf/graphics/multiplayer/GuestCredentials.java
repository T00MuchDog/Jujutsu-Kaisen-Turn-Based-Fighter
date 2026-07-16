package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.SessionIdentity;

import java.util.Objects;

/** Persisted guest identity metadata and its private raw bearer token. */
public record GuestCredentials(SessionIdentity identity, String token) {
    public GuestCredentials {
        Objects.requireNonNull(identity, "identity");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
    }

    @Override
    public String toString() {
        return "GuestCredentials[identity=" + identity + ", token=<redacted>]";
    }
}
