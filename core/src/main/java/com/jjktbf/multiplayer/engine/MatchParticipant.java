package com.jjktbf.multiplayer.engine;

import com.jjktbf.model.character.Character;
import com.jjktbf.multiplayer.protocol.PlayerSide;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical participant input used to create an authoritative battle session.
 *
 * <p>{@code characters} is the ordered fighter roster brought into the match
 * (one entry for 1v1, two for 2v2). {@link #primaryCharacter()} is the first
 * entry for legacy single-fighter callers.
 */
public record MatchParticipant(
    String playerId,
    String displayName,
    List<Character> characters,
    PlayerSide side
) {
    public MatchParticipant {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId cannot be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        Objects.requireNonNull(side, "side");
        if (characters == null || characters.isEmpty()) {
            throw new IllegalArgumentException("characters cannot be empty");
        }
        characters = List.copyOf(characters);
        // Reject duplicate canonical ids so a roster never fields the same
        // character twice.
        Set<String> seen = new LinkedHashSet<>();
        for (Character character : characters) {
            Objects.requireNonNull(character, "character");
            if (!seen.add(character.getId())) {
                throw new IllegalArgumentException(
                    "A participant roster cannot contain duplicate characters: "
                        + character.getId());
            }
        }
    }

    /** Legacy single-fighter constructor. */
    public MatchParticipant(String playerId, String displayName, Character character, PlayerSide side) {
        this(playerId, displayName, character == null ? List.of() : List.of(character), side);
    }

    /** The first (primary) fighter in roster order. */
    public Character primaryCharacter() {
        return characters.get(0);
    }

    /** Ordered canonical ids of the roster, in team order. */
    public List<String> characterIds() {
        return characters.stream().map(Character::getId).toList();
    }
}
