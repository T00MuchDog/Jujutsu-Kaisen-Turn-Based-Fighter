package com.jjktbf.multiplayer.protocol;

import java.util.List;

/** Ordered canonical fighter roster selected after a challenge is accepted. */
public record MatchCharacterSelectionRequest(List<String> characterIds) {
    public MatchCharacterSelectionRequest {
        characterIds = characterIds == null ? List.of() : List.copyOf(characterIds);
    }
}
