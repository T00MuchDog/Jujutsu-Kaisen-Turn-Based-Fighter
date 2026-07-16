package com.jjktbf.server.content;

/** Immutable public metadata for a canonical playable character. */
public record CharacterSummary(
    String characterId,
    String name,
    String description
) {
}
