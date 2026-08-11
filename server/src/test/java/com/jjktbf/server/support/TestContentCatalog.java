package com.jjktbf.server.support;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.move.Move;
import com.jjktbf.server.content.ContentCatalog;

import java.util.List;
import java.util.stream.IntStream;

public final class TestContentCatalog {
    private TestContentCatalog() {
    }

    public static ContentCatalog create() {
        Move move = new Move.Builder("test-move")
            .name("Test Move")
            .basePower(10)
            .unleashPoint(1)
            .build();
        CharacterStats stats = new CharacterStats.Builder().build();
        return ContentCatalog.of(IntStream.range(0, 6)
            .mapToObj(index -> new SorcererCharacter(
                "test-character-" + index,
                "Test Character " + index,
                stats,
                null,
                List.of(move)))
            .toList());
    }
}
