package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Input;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.multiplayer.protocol.ActionSegmentState;
import com.jjktbf.multiplayer.protocol.ActionSegmentStatus;
import com.jjktbf.multiplayer.protocol.PlanBoard;
import com.jjktbf.multiplayer.protocol.PlanPlacement;
import com.jjktbf.multiplayer.protocol.PlanState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamPlanningPanelTest {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 900;

    @Test
    void navigationPreservesEachPageDraftAndProducesOneAtomicPlacementList() {
        Move firstMove = move("FIRST");
        Move secondMove = move("SECOND");
        TeamPlanningPanel panel = panel(firstMove, secondMove);

        ActionSegment first = panel.activePlanningPanel()
            .restorePlacement(firstMove, 1, 0, "enemy-1");
        assertEquals("First", panel.activePageName());
        assertEquals(0, panel.activePageIndex());

        assertTrue(panel.inputProcessor().keyDown(Input.Keys.RIGHT));
        ActionSegment second = panel.activePlanningPanel()
            .restorePlacement(secondMove, 11, 0, "enemy-2");
        assertEquals("Second", panel.activePageName());
        assertTrue(panel.inputProcessor().keyDown(Input.Keys.LEFT));

        assertEquals(List.of(first), panel.activePlanningPanel().getPlan().allSegments());
        panel.nextPage();
        assertEquals(List.of(second), panel.activePlanningPanel().getPlan().allSegments());
        List<PlanPlacement> placements = panel.getPlacements();
        assertEquals(List.of("actor-1", "actor-2"),
            placements.stream().map(PlanPlacement::actorId).toList());
        assertEquals(List.of("enemy-1", "enemy-2"),
            placements.stream().map(PlanPlacement::targetId).toList());
        assertEquals(List.of(new CombatantId("actor-1"), new CombatantId("actor-2")),
            panel.getTeamPlan().actors());
    }

    @Test
    void confirmationRunsOnlyAfterEveryPageLocksAndCannotReopenWhileSubmitted() {
        TeamPlanningPanel panel = panel(move("FIRST"), move("SECOND"));
        AtomicInteger confirmations = new AtomicInteger();
        panel.setOnConfirm(confirmations::incrementAndGet);

        clickLock(panel);
        assertTrue(panel.activePlanningPanel().isConfirmed());
        assertEquals(0, confirmations.get());
        panel.nextPage();
        clickLock(panel);
        assertEquals(1, confirmations.get());

        panel.previousPage();
        clickLock(panel);
        assertTrue(panel.activePlanningPanel().isConfirmed());
        assertEquals(1, confirmations.get());

        panel.unlock();
        assertFalse(panel.activePlanningPanel().isConfirmed());
        panel.nextPage();
        assertFalse(panel.activePlanningPanel().isConfirmed());
    }

    @Test
    void reconnectRestoresActorAndTargetIntentFromAuthoritativePlan() {
        Move move = move("RESTORED");
        ActionSegmentState segment = new ActionSegmentState(
            "segment-1",
            move.getId(),
            move.getName(),
            PlanBoard.OFFENSIVE,
            7,
            16,
            7,
            10,
            0,
            ActionSegmentStatus.QUEUED,
            null,
            "actor-1",
            "enemy-2"
        );
        PlanState restored = new PlanState(1, 150, 10, 0, 0, List.of(segment), List.of());
        TeamPlanningPanel panel = new TeamPlanningPanel(
            BattleTeamId.PLAYER,
            300,
            List.of(spec("actor-1", "First", move, restored)),
            null,
            WIDTH,
            HEIGHT
        );

        PlanPlacement placement = panel.getPlacements().get(0);
        assertEquals("actor-1", placement.actorId());
        assertEquals("enemy-2", placement.targetId());
        assertEquals(7, placement.startTick());
    }

    private static TeamPlanningPanel panel(Move first, Move second) {
        return new TeamPlanningPanel(
            BattleTeamId.PLAYER,
            300,
            List.of(
                spec("actor-1", "First", first, null),
                spec("actor-2", "Second", second, null)
            ),
            null,
            WIDTH,
            HEIGHT
        );
    }

    private static TeamPlanningPanel.PageSpec spec(
        String actorId,
        String name,
        Move move,
        PlanState restored
    ) {
        return new TeamPlanningPanel.PageSpec(
            actorId,
            name,
            List.of(move),
            Map.of(move.getId(), 0),
            150,
            0,
            null,
            Map.of(),
            List.of(
                new PlanningPanel.TargetOption("enemy-1", "Enemy 1"),
                new PlanningPanel.TargetOption("enemy-2", "Enemy 2")
            ),
            restored
        );
    }

    private static void clickLock(TeamPlanningPanel panel) {
        panel.activePlanningPanel().inputProcessor()
            .touchDown(820, HEIGHT - 830, 0, Input.Buttons.LEFT);
    }

    private static Move move(String id) {
        MoveData data = new MoveData();
        data.id = id;
        data.name = id;
        data.tags = List.of("ATTACK");
        data.basePower = 10;
        data.apCost = 10;
        data.unleashPoint = 1;
        return data.toMove();
    }
}
