package com.jjktbf.server.challenge;

import com.jjktbf.model.character.Character;
import com.jjktbf.multiplayer.protocol.PlayerSide;

import java.util.List;
import java.util.Objects;

/**
 * Canonical participant data used to construct a later authoritative match.
 *
 * <p>{@code characterIds}/{@code characters} form an ordered roster (one entry
 * for 1v1, two for 2v2); {@link #primaryCharacterId()}/{@link #primaryCharacter()}
 * return the first entry for legacy single-fighter callers.
 */
public record AcceptedMatchParticipant(
    String playerId,
    String displayName,
    PlayerSide side,
    List<String> characterIds,
    List<Character> characters
) {
    public AcceptedMatchParticipant {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(side, "side");
        if (characterIds == null || characterIds.isEmpty()) {
            throw new IllegalArgumentException("characterIds cannot be empty");
        }
        if (characters == null || characters.isEmpty()) {
            throw new IllegalArgumentException("characters cannot be empty");
        }
        characterIds = List.copyOf(characterIds);
        characters = List.copyOf(characters);
    }

    /** Legacy single-fighter constructor. */
    public AcceptedMatchParticipant(
        String playerId,
        String displayName,
        PlayerSide side,
        String characterId,
        Character character
    ) {
        this(playerId, displayName, side,
            characterId == null ? List.of() : List.of(characterId),
            character == null ? List.of() : List.of(character));
    }

    /** The first (primary) fighter's canonical id. */
    public String primaryCharacterId() {
        return characterIds.get(0);
    }

    /** The first (primary) fighter. */
    public Character primaryCharacter() {
        return characters.get(0);
    }
}
