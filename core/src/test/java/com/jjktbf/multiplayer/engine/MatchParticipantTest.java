package com.jjktbf.multiplayer.engine;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates the N-fighter roster contract on {@link MatchParticipant}: an
 * ordered roster is accepted and exposed, and degenerate rosters are rejected.
 */
class MatchParticipantTest {

    private static Character character(String id) {
        return new SorcererCharacter(id, id, new CharacterStats.Builder().build(), null, List.of());
    }

    @Test
    void rosterIsExposedInOrderWithPrimaryAccessors() {
        Character first = character("000001");
        Character second = character("000002");
        MatchParticipant participant = new MatchParticipant(
            "player-1", "Player One", List.of(first, second), PlayerSide.PLAYER_ONE);

        assertEquals(List.of(first, second), participant.characters());
        assertEquals(List.of("000001", "000002"), participant.characterIds());
        assertEquals(first, participant.primaryCharacter());
        assertEquals("000001", participant.primaryCharacter().getId());
    }

    @Test
    void emptyRosterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MatchParticipant(
            "player-1", "Player One", List.of(), PlayerSide.PLAYER_ONE));
    }

    @Test
    void duplicateCanonicalIdInRosterIsRejected() {
        Character sameId = character("000001");
        Character repeated = character("000001");
        assertThrows(IllegalArgumentException.class, () -> new MatchParticipant(
            "player-1", "Player One", List.of(sameId, repeated), PlayerSide.PLAYER_ONE));
    }

    @Test
    void legacySingleCharacterConstructorFieldsOneFighter() {
        Character solo = character("000001");
        MatchParticipant participant = new MatchParticipant(
            "player-1", "Player One", solo, PlayerSide.PLAYER_ONE);

        assertEquals(1, participant.characters().size());
        assertEquals(solo, participant.primaryCharacter());
        assertEquals(List.of("000001"), participant.characterIds());
    }
}
