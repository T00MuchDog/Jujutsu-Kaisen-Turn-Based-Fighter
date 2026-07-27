package com.jjktbf.graphics.screens;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.multiplayer.protocol.ActionSegmentState;
import com.jjktbf.multiplayer.protocol.ActionSegmentStatus;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlanBoard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleScreenMoveStateTest {
    @Test
    void defensiveBoardDoesNotTurnUtilityMoveIntoDefense() {
        Move utility = BattleScreen.toDisplayMove(moveState(
            MoveCategory.UTILITY, List.of("UTILITY"), PlanBoard.DEFENSIVE));

        assertTrue(utility.hasTag("UTILITY"));
        assertFalse(utility.hasTag("DEFENSIVE"));
        assertEquals(BattlePlan.Board.DEFENSIVE, BattlePlan.boardFor(utility));
    }

    @Test
    void rawNatureTagsSurviveDisplayReconstruction() {
        Move defense = BattleScreen.toDisplayMove(moveState(
            MoveCategory.DEFENSIVE,
            List.of("PHYSICAL", "DEFENSIVE"),
            PlanBoard.DEFENSIVE
        ));

        assertTrue(defense.hasTag("PHYSICAL"));
        assertTrue(defense.hasTag("DEFENSIVE"));
    }

    @Test
    void actionTicksSkipGapsAndStunnedSegments() {
        List<ActionSegmentState> segments = List.of(
            actionSegment("FIRST", 1, 2, ActionSegmentStatus.RESOLVED),
            actionSegment("STUNNED", 4, 5, ActionSegmentStatus.STUNNED),
            actionSegment("LAST", 8, 8, ActionSegmentStatus.RESOLVED)
        );

        assertEquals(List.of(1, 2, 8), BattleScreen.actionTicks(segments));
    }

    private static MoveState moveState(
        MoveCategory category,
        List<String> tags,
        PlanBoard board
    ) {
        return new MoveState(
            "MOVE",
            "Move",
            "Description",
            category.name(),
            tags,
            board,
            0,
            1.0,
            true,
            5,
            1,
            false,
            0,
            0,
            0,
            0,
            true,
            null
        );
    }

    private static ActionSegmentState actionSegment(
        String id,
        int startTick,
        int endTick,
        ActionSegmentStatus status
    ) {
        return new ActionSegmentState(
            id,
            id,
            id,
            PlanBoard.OFFENSIVE,
            startTick,
            endTick,
            startTick,
            endTick - startTick + 1,
            0,
            status,
            startTick
        );
    }
}
