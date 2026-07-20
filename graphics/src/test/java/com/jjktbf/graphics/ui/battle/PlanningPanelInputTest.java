package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Input.Buttons;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningPanelInputTest {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 900;

    @Test
    void clickingCardPlacesMoveAtFirstFreeTickOnItsAssignedTimeline() {
        Move cardMove = move("CARD", 10);
        Move existingMove = move("EXISTING", 10);
        PlanningPanel panel = panel(cardMove, 150);
        assertNotNull(panel.getPlan().place(existingMove, 1, 0));

        clickCard(panel.inputProcessor());

        ActionSegment placed = panel.getPlan().offensiveTimeline().getSegments().stream()
            .filter(segment -> segment.getMove() == cardMove)
            .findFirst()
            .orElseThrow();
        assertEquals(11, placed.getStartTick());
        assertEquals(0, panel.getPlan().defensiveTimeline().getSegments().size());
    }

    @Test
    void clickingCardDoesNothingWhenItsTimelineHasNoFreeRange() {
        Move cardMove = move("FULL", 150);
        PlanningPanel panel = panel(cardMove, 300);

        clickCard(panel.inputProcessor());
        clickCard(panel.inputProcessor());

        assertEquals(1, panel.getPlan().offensiveTimeline().getSegments().size());
        assertEquals(150, panel.getPlan().totalApUsed());
    }

    @Test
    void rightClickingSegmentRemovesItAndRefundsItsBudget() {
        Move move = move("REMOVE", 10);
        PlanningPanel panel = panel(move, 150);
        assertNotNull(panel.getPlan().place(move, 1, 0));

        PlanningPanel.PlanningInputProcessor input = panel.inputProcessor();
        assertTrue(input.touchDown(160, HEIGHT - 580, 0, Buttons.RIGHT));

        assertEquals(0, panel.getPlan().offensiveTimeline().getSegments().size());
        assertEquals(0, panel.getPlan().totalApUsed());
    }

    private static PlanningPanel panel(Move move, int apBudget) {
        return new PlanningPanel(
            List.of(move), Map.of(move.getId(), 0), apBudget, 0,
            null, null, WIDTH, HEIGHT
        );
    }

    private static void clickCard(PlanningPanel.PlanningInputProcessor input) {
        input.touchDown(50, HEIGHT - 50, 0, Buttons.LEFT);
        input.touchUp(50, HEIGHT - 50, 0, Buttons.LEFT);
    }

    private static Move move(String id, int apCost) {
        MoveData data = new MoveData();
        data.id = id;
        data.name = id;
        data.tags = List.of("ATTACK");
        data.apCost = apCost;
        data.unleashPoint = 1;
        return data.toMove();
    }
}
