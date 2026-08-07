package com.jjktbf;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AoeTagTest {

    @Test
    void movesWithoutAoeAreSingleTarget() {
        Move move = new Move.Builder("SINGLE_TARGET")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .build();

        assertFalse(move.isAoe());
        assertTrue(move.isSingleTarget());
    }

    @Test
    void aoeTagIsQueryableAndSurvivesDataRoundTrip() {
        Move original = new Move.Builder("AOE")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .build();

        assertTrue(original.isAoe());
        assertFalse(original.isSingleTarget());
        assertTrue(original.hasTag("AOE"));

        Move restored = MoveData.fromMove(original).toMove();
        assertTrue(restored.isAoe());
        assertFalse(restored.isSingleTarget());
    }

    @Test
    void friendlyFireCannotBeAuthoredWithoutAoe() {
        assertThrows(IllegalStateException.class, () -> new Move.Builder("INVALID_FF")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.FRIENDLY_FIRE))
            .build());
    }
}
