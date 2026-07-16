package com.jjktbf.multiplayer.protocol;

/** Result of creating a guest, including the token shown only to that guest. */
public record GuestCreateResponse(
    SessionIdentity identity,
    String token
) {
}
