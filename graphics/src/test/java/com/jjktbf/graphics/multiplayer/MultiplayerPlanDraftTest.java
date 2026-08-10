package com.jjktbf.graphics.multiplayer;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.multiplayer.protocol.HitComponentState;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlanBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerPlanDraftTest {
    @Test
    void firstFitUsesIndependentServerDeclaredBoards() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 40, 20);

        assertEquals(1, draft.addFirstFit(move("attack-1", PlanBoard.OFFENSIVE, 8, 0))
            .placement().startTick());
        assertEquals(9, draft.addFirstFit(move("attack-2", PlanBoard.OFFENSIVE, 5, 0))
            .placement().startTick());
        assertEquals(1, draft.addFirstFit(move("guard", PlanBoard.DEFENSIVE, 10, 0))
            .placement().startTick());

        assertEquals(List.of(1, 9, 1), draft.toIntent().stream()
            .map(placement -> placement.startTick()).toList());
    }

    @Test
    void fullBoardReportsOverlapWithoutCreatingIntent() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 400, 20);
        assertTrue(draft.addFirstFit(
            move("full", PlanBoard.OFFENSIVE, BattlePlan.GRID_LENGTH, 0)).added());

        MultiplayerPlanDraft.AddResult result = draft.addFirstFit(
            move("overlap", PlanBoard.OFFENSIVE, 1, 0));

        assertEquals(MultiplayerPlanDraft.AddStatus.BOARD_FULL, result.status());
        assertEquals(1, draft.placements().size());
    }

    @Test
    void enforcesPreviewApAndEffectiveCeBudgets() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 10, 5);

        assertTrue(draft.addFirstFit(move("first", PlanBoard.OFFENSIVE, 6, 3)).added());
        assertEquals(MultiplayerPlanDraft.AddStatus.INSUFFICIENT_AP,
            draft.addFirstFit(move("ap", PlanBoard.DEFENSIVE, 5, 0)).status());
        assertEquals(MultiplayerPlanDraft.AddStatus.INSUFFICIENT_CE,
            draft.addFirstFit(move("ce", PlanBoard.DEFENSIVE, 4, 3)).status());
        assertEquals(6, draft.apUsed());
        assertEquals(3, draft.ceUsed());
    }

    @Test
    void undoAndClearRefundDraftBudgets() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(3, 20, 10);
        draft.addFirstFit(move("one", PlanBoard.OFFENSIVE, 6, 2));
        draft.addFirstFit(move("two", PlanBoard.DEFENSIVE, 5, 4));

        assertTrue(draft.undo());
        assertEquals(6, draft.apUsed());
        assertEquals(2, draft.ceUsed());
        draft.clear();
        assertTrue(draft.placements().isEmpty());
        assertEquals(20, draft.remainingAp());
        assertEquals(10, draft.remainingCe());
        assertFalse(draft.undo());
    }

    @Test
    void onlyAnAuthoritativeNewRoundClearsTheDraft() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 20, 10);
        draft.addFirstFit(move("one", PlanBoard.OFFENSIVE, 6, 2));

        assertFalse(draft.beginRound(1, 22, 11));
        assertEquals(1, draft.placements().size());
        assertTrue(draft.beginRound(2, 25, 12));
        assertTrue(draft.placements().isEmpty());
        assertEquals(25, draft.remainingAp());
        assertEquals(12, draft.remainingCe());
    }

    @Test
    void serverRestrictedMoveCannotEnterTheDraft() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 20, 20);
        MoveState restricted = move(
            "restricted", PlanBoard.OFFENSIVE, 5, 0, false);

        MultiplayerPlanDraft.AddResult result = draft.addFirstFit(restricted);

        assertEquals(MultiplayerPlanDraft.AddStatus.MOVE_RESTRICTED, result.status());
        assertFalse(draft.canAdd(restricted));
        assertTrue(draft.placements().isEmpty());
    }

    @Test
    void finalImpactMustFitInsideTheTimeline() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 200, 20);
        MoveState delayed = new MoveState(
            "delayed",
            "delayed",
            "Delayed final hit",
            "PHYSICAL",
            List.of("ATTACK", "PHYSICAL"),
            PlanBoard.OFFENSIVE,
            10,
            List.of(new HitComponentState(
                10, "PHYSICAL", List.of("PHYSICAL"), 1, false, true, 1.0)),
            1.0,
            true,
            BattlePlan.GRID_LENGTH,
            BattlePlan.GRID_LENGTH,
            false,
            0,
            0,
            0,
            0,
            true,
            null
        );

        assertEquals(0, draft.lastStartTick(delayed));
        assertEquals(MultiplayerPlanDraft.AddStatus.BOARD_FULL,
            draft.addFirstFit(delayed, null, List.of("enemy")).status());
        assertFalse(draft.canAdd(delayed, List.of("enemy")));
    }

    @Test
    void moveCapDisablesFurtherDraftPlacementsUntilReset() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 30, 0);
        MoveState capped = new MoveState(
            "capped", "capped", "Once", "UTILITY", List.of("UTILITY"),
            PlanBoard.DEFENSIVE, 0, List.of(), 1.0, true,
            5, 1, false, 0, 0, 0, 0, 1, true, null);

        assertTrue(draft.addFirstFit(capped).added());
        assertEquals(MultiplayerPlanDraft.AddStatus.MOVE_CAP_REACHED,
            draft.addFirstFit(capped).status());
        assertFalse(draft.canAdd(capped));

        draft.clear();
        assertTrue(draft.canAdd(capped));
    }

    @Test
    void battleGridLengthScalesPlacementBounds() {
        MultiplayerPlanDraft starter = new MultiplayerPlanDraft();
        starter.beginRound(1, 200, 20, 60);
        MoveState dot = move("dot", PlanBoard.OFFENSIVE, 1, 0);
        // A 1-AP move placed at tick 60 fills a 60-dot starter grid exactly.
        assertTrue(starter.addFirstFit(dot).added());

        MultiplayerPlanDraft topTier = new MultiplayerPlanDraft();
        topTier.beginRound(1, 200, 20, 300);
        // The same move still fits easily on the 300-dot top-tier grid.
        assertTrue(topTier.addFirstFit(dot).added());
    }

    @Test
    void multipleTargetIntentRequiresDistinctTargetsWithinServerDeclaredCap() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 20, 0);
        MoveState multiple = multipleMove("cursed-speech", 3);
        Assumptions.assumeTrue(multiple != null, "requires MoveState AOE protocol fields");

        assertFalse(draft.canAdd(multiple));
        assertEquals(MultiplayerPlanDraft.AddStatus.INVALID_TARGET_SELECTION,
            draft.addFirstFit(multiple).status());
        assertEquals(MultiplayerPlanDraft.AddStatus.INVALID_TARGET_SELECTION,
            draft.addFirstFit(multiple, "actor-1", List.of("one", "one")).status());
        assertEquals(MultiplayerPlanDraft.AddStatus.INVALID_TARGET_SELECTION,
            draft.addFirstFit(multiple, "actor-1", List.of("one", "two", "three", "four"))
                .status());

        assertTrue(draft.canAdd(multiple, List.of("three", "one")));
        MultiplayerPlanDraft.AddResult result = draft.addFirstFit(
            multiple, "actor-1", List.of("three", "one"));
        assertTrue(result.added());
        assertEquals("actor-1", result.placement().actorId());
        assertEquals(List.of("three", "one"), result.placement().targetIds());
        assertEquals(List.of("three", "one"),
            TargetListSupport.targetIds(draft.toIntent().get(0)));
    }

    @Test
    void targetValidationMatchesServerTargetingShapes() {
        MultiplayerPlanDraft draft = new MultiplayerPlanDraft();
        draft.beginRound(1, 40, 0);
        MoveState single = targetedMove("single", List.of("ATTACK", "PHYSICAL"), null, 0);

        assertFalse(draft.canAdd(single));
        assertFalse(draft.canAdd(single, List.of("one", "two")));
        assertTrue(draft.canAdd(single, List.of("one")));

        MoveState derived = targetedMove(
            "all", List.of("ATTACK", "AOE", "PHYSICAL"), "ALL_ENEMIES", 0);
        assertTrue(draft.canAdd(derived));
        assertFalse(draft.canAdd(derived, List.of("one")));

        MoveState utility = targetedMove("utility", List.of("UTILITY"), null, 0);
        assertTrue(draft.canAdd(utility));
        assertFalse(draft.canAdd(utility, List.of("one")));
    }

    private static MoveState move(String id, PlanBoard board, int apCost, int ceCost) {
        return move(id, board, apCost, ceCost, true);
    }

    private static MoveState move(
        String id,
        PlanBoard board,
        int apCost,
        int ceCost,
        boolean available
    ) {
        return new MoveState(
            id,
            id,
            "Test move",
            board == PlanBoard.OFFENSIVE ? "ATTACK" : "DEFENSE",
            List.of(),
            board,
            10,
            1.0,
            true,
            apCost,
            1,
            ceCost > 0,
            ceCost,
            ceCost,
            ceCost,
            ceCost,
            available,
            available ? null : "Restricted for test"
        );
    }

    private static MoveState multipleMove(String id, int targetCount) {
        try {
            return MoveState.class.getConstructor(
                String.class, String.class, String.class, String.class, List.class,
                PlanBoard.class, int.class, List.class, double.class, boolean.class,
                int.class, int.class, boolean.class, int.class, int.class, int.class,
                int.class, int.class, boolean.class, String.class, String.class,
                List.class, String.class, int.class)
                .newInstance(
                    id, id, "Multiple targets", "PHYSICAL",
                    List.of("ATTACK", "AOE", "PHYSICAL"), PlanBoard.OFFENSIVE,
                    10, List.of(), 1.0, true, 5, 1, false, 0, 0, 0, 0, 0,
                    true, null, null, List.of(), "MULTIPLE", targetCount);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static MoveState targetedMove(
        String id,
        List<String> tags,
        String aoeType,
        int targetCount
    ) {
        return new MoveState(
            id, id, "Targeted move", "PHYSICAL", tags, PlanBoard.OFFENSIVE,
            10, List.of(), 1.0, true, 5, 1, false, 0, 0, 0, 0, 0,
            true, null, null, List.of(), aoeType, targetCount);
    }
}
