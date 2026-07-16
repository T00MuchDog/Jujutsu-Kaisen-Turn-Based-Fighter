package com.jjktbf.graphics.multiplayer;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlanBoard;
import org.junit.jupiter.api.Test;

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
}
