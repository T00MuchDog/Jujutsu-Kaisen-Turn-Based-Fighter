package com.jjktbf.graphics.screens;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.multiplayer.protocol.ActionSegmentState;
import com.jjktbf.multiplayer.protocol.ActionSegmentStatus;
import com.jjktbf.multiplayer.protocol.HitComponentState;
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
    void rawTechniqueTagsRetainDisplayPrerequisitesAcrossMoveRoles() {
        Move utility = BattleScreen.toDisplayMove(moveState(
            MoveCategory.UTILITY,
            List.of("UTILITY", "CURSED_ENERGY", "INNATE_TECHNIQUE"),
            PlanBoard.DEFENSIVE
        ));

        assertEquals(0, utility.getPrerequisites().get("cursedTechniqueMastery"));
        assertEquals("ONLINE_DISPLAY", utility.getRequiredTechniqueId());
    }

    @Test
    void orderedHitComponentsSurviveDisplayReconstruction() {
        Move move = BattleScreen.toDisplayMove(moveState(
            MoveCategory.PHYSICAL_CURSED_ENERGY,
            List.of("PHYSICAL", "CURSED_ENERGY", "ATTACK", "STUN"),
            PlanBoard.OFFENSIVE,
            999,
            List.of(
                new HitComponentState(
                    40, "PHYSICAL", List.of("PHYSICAL"), 0, false, true, 1.0),
                new HitComponentState(
                    25, "CURSED_ENERGY", List.of("CURSED_ENERGY"), 4, true, false, 1.0))
        ));

        assertEquals(65, move.getBasePower());
        assertEquals(2, move.getHitComponents().size());
        assertEquals(MoveCategory.PHYSICAL, move.getHitComponents().get(0).getCategory());
        assertEquals(MoveCategory.CURSED_ENERGY,
            move.getHitComponents().get(1).getCategory());
        assertEquals(4, move.getHitComponents().get(1).getDelayTicks());
        assertTrue(move.getHitComponents().get(1).requiresPreviousConnection());
        assertFalse(move.getHitComponents().get(1).isAvoidable());
        assertTrue(move.getTags().contains(MoveTag.STUN));
    }

    @Test
    void absentHitComponentsUseLegacyBasePower() {
        Move move = BattleScreen.toDisplayMove(moveState(
            MoveCategory.PHYSICAL,
            List.of("PHYSICAL", "ATTACK"),
            PlanBoard.OFFENSIVE,
            37,
            List.of()
        ));

        assertEquals(37, move.getBasePower());
        assertEquals(1, move.getHitComponents().size());
        assertEquals(37, move.getHitComponents().get(0).getBasePower());
        assertEquals(0, move.getHitComponents().get(0).getDelayTicks());
    }

    @Test
    void moveCapSurvivesOnlineDisplayReconstruction() {
        MoveState state = new MoveState(
            "CAPPED", "Capped", "Once", MoveCategory.UTILITY.name(),
            List.of("PHYSICAL", "UTILITY"), PlanBoard.DEFENSIVE,
            0, List.of(), 1.0, true, 5, 1, false,
            0, 0, 0, 0, 1, true, null);

        assertEquals(1, BattleScreen.toDisplayMove(state).getMoveCap());
    }

    @Test
    void summonDefinitionSurvivesOnlineDisplayReconstruction() {
        MoveState state = new MoveState(
            "SUMMON", "Summon", "Manifest", MoveCategory.UTILITY.name(),
            List.of("UTILITY"), PlanBoard.DEFENSIVE,
            0, List.of(), 1.0, true, 5, 1, true,
            24, 24, 12, 42, 1, true, null, "DOG", List.of("DOG"));

        assertEquals("DOG", BattleScreen.toDisplayMove(state).getSummonCharacterId());
    }

    @Test
    void summonEffectDefinitionsSurviveOnlineDisplayReconstruction() {
        MoveState state = new MoveState(
            "SUMMON_EFFECT", "Summon Effect", "Manifest on hit", MoveCategory.UTILITY.name(),
            List.of("UTILITY"), PlanBoard.DEFENSIVE,
            0, List.of(), 1.0, true, 5, 1, true,
            24, 24, 12, 42, 1, true, null, null, List.of("TOAD"));

        Move move = BattleScreen.toDisplayMove(state);

        assertEquals(List.of("TOAD"),
            com.jjktbf.model.combat.MoveAvailability.summonedDefinitionIds(move));
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

    @Test
    void actionTicksIncludeDelayedImpactsAfterApOccupancy() {
        ActionSegmentState delayed = new ActionSegmentState(
            "DELAYED", "DELAYED", "Delayed", PlanBoard.OFFENSIVE,
            1, 3, 1, 3, 0, ActionSegmentStatus.RESOLVED, 6);

        assertEquals(List.of(1, 2, 3, 4, 5, 6),
            BattleScreen.actionTicks(List.of(delayed)));
    }

    private static MoveState moveState(
        MoveCategory category,
        List<String> tags,
        PlanBoard board
    ) {
        return moveState(category, tags, board, 0, List.of());
    }

    private static MoveState moveState(
        MoveCategory category,
        List<String> tags,
        PlanBoard board,
        int basePower,
        List<HitComponentState> hitComponents
    ) {
        return new MoveState(
            "MOVE",
            "Move",
            "Description",
            category.name(),
            tags,
            board,
            basePower,
            hitComponents,
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
