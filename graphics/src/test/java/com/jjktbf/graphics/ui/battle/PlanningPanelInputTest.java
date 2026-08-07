package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Input.Buttons;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // A 150-dot grid is exactly filled by one 150-AP move, so a second
        // placement is rejected by the grid (no free range) even though AP
        // would still allow it.
        PlanningPanel panel = panel(cardMove, 300, 150);

        clickCard(panel.inputProcessor());
        clickCard(panel.inputProcessor());

        assertEquals(1, panel.getPlan().offensiveTimeline().getSegments().size());
        assertEquals(150, panel.getPlan().totalApUsed());
    }

    @Test
    void rightClickingSegmentRemovesItAndRefundsItsBudget() {
        Move move = move("REMOVE", 10);
        // Top-tier grid keeps the bar at full width so the fixed click
        // coordinates land on the placed segment.
        PlanningPanel panel = panel(move, 150, 300);
        assertNotNull(panel.getPlan().place(move, 1, 0));
        List<SoundCue> cues = new ArrayList<>();
        panel.setSoundPlayer(cues::add);

        PlanningPanel.PlanningInputProcessor input = panel.inputProcessor();
        assertTrue(input.touchDown(160, HEIGHT - 580, 0, Buttons.RIGHT));

        assertEquals(0, panel.getPlan().offensiveTimeline().getSegments().size());
        assertEquals(0, panel.getPlan().totalApUsed());
        assertEquals(List.of(SoundCue.UI_PLAN_REMOVE), cues);
    }

    @Test
    void successfulCardPlacementEmitsPlannerFeedback() {
        Move move = move("FEEDBACK", 10);
        PlanningPanel panel = panel(move, 150);
        List<SoundCue> cues = new ArrayList<>();
        panel.setSoundPlayer(cues::add);

        clickCard(panel.inputProcessor());

        assertEquals(List.of(SoundCue.UI_PLAN_PLACE), cues);
    }

    @Test
    void dragStartsOnlyAfterThresholdAndThenEmitsPickupBeforePlacement() {
        Move move = move("DRAG", 10);
        // Top-tier grid keeps the bar at full width so the fixed drag
        // coordinates land on the timeline.
        PlanningPanel panel = panel(move, 150, 300);
        List<SoundCue> cues = new ArrayList<>();
        panel.setSoundPlayer(cues::add);
        PlanningPanel.PlanningInputProcessor input = panel.inputProcessor();

        input.touchDown(50, HEIGHT - 50, 0, Buttons.LEFT);
        input.touchDragged(52, HEIGHT - 52, 0);
        input.touchUp(52, HEIGHT - 52, 0, Buttons.LEFT);
        assertEquals(List.of(SoundCue.UI_PLAN_PLACE), cues);

        cues.clear();
        input.touchDown(50, HEIGHT - 50, 0, Buttons.LEFT);
        input.touchDragged(300, HEIGHT - 580, 0);
        input.touchUp(300, HEIGHT - 580, 0, Buttons.LEFT);
        assertEquals(List.of(SoundCue.UI_PICKUP, SoundCue.UI_PLAN_PLACE), cues);
    }

    @Test
    void clickingExistingSegmentSelectsItWithoutMovingOrSounding() {
        Move move = move("SELECT", 10);
        PlanningPanel panel = panel(move, 150);
        ActionSegment original = panel.getPlan().place(move, 1, 0);
        assertNotNull(original);
        List<SoundCue> cues = new ArrayList<>();
        panel.setSoundPlayer(cues::add);
        PlanningPanel.PlanningInputProcessor input = panel.inputProcessor();

        input.touchDown(160, HEIGHT - 580, 0, Buttons.LEFT);
        input.touchUp(160, HEIGHT - 580, 0, Buttons.LEFT);

        assertEquals(List.of(original), panel.getPlan().offensiveTimeline().getSegments());
        assertTrue(cues.isEmpty());
    }

    @Test
    void dragPrecheckIncludesTheFinalHitDelay() {
        MoveData data = new MoveData();
        data.id = "DELAYED";
        data.name = "Delayed";
        data.tags = List.of("ATTACK", "PHYSICAL");
        data.apCost = 5;
        data.unleashPoint = 5;
        MoveData.HitComponentData hit = new MoveData.HitComponentData();
        hit.basePower = 10;
        hit.tags = List.of("PHYSICAL");
        hit.delayTicks = 5;
        data.hitComponents = List.of(hit);

        assertEquals(1, PlanningPanel.lastStartTick(data.toMove(), 10));
    }

    @Test
    void targetSelectionIsValidatedAndIncludedInWirePlacements() {
        Move move = move("TARGETED", 10);
        PlanningPanel panel = targetedPanel(move);
        ActionSegment segment = panel.restorePlacement(move, 1, 0, null);

        assertNotNull(segment);
        assertFalse(panel.chooseTarget(segment, "unknown"));
        assertTrue(panel.chooseTarget(segment, "target-1"));
        assertEquals(new CombatantId("target-1"), segment.getTarget());
        assertEquals("actor-1", panel.getPlacements().get(0).actorId());
        assertEquals("target-1", panel.getPlacements().get(0).targetId());
    }

    @Test
    void relocatingSegmentPreservesItsSelectedTarget() {
        Move move = move("RELOCATE_TARGETED", 10);
        PlanningPanel panel = targetedPanel(move);
        assertNotNull(panel.restorePlacement(move, 1, 0, "target-1"));
        PlanningPanel.PlanningInputProcessor input = panel.inputProcessor();

        input.touchDown(160, HEIGHT - 580, 0, Buttons.LEFT);
        input.touchDragged(300, HEIGHT - 580, 0);
        input.touchUp(300, HEIGHT - 580, 0, Buttons.LEFT);

        ActionSegment relocated = panel.getPlan().offensiveTimeline().getSegments().get(0);
        assertEquals(new CombatantId("target-1"), relocated.getTarget());
    }

    @Test
    void movePaletteFillsTenCardsAcrossTwoRowsBeforeExtendingRight() {
        assertEquals(1, PlanningPanel.paletteRowCount(5));
        assertEquals(2, PlanningPanel.paletteRowCount(6));
        assertEquals(2, PlanningPanel.paletteRowCount(30));

        assertEquals(0, PlanningPanel.paletteRow(0));
        assertEquals(4, PlanningPanel.paletteColumn(4));
        assertEquals(1, PlanningPanel.paletteRow(5));
        assertEquals(0, PlanningPanel.paletteColumn(5));
        assertEquals(1, PlanningPanel.paletteRow(9));
        assertEquals(4, PlanningPanel.paletteColumn(9));

        assertEquals(0, PlanningPanel.paletteRow(10));
        assertEquals(5, PlanningPanel.paletteColumn(10));
        assertEquals(1, PlanningPanel.paletteRow(11));
        assertEquals(5, PlanningPanel.paletteColumn(11));
        assertEquals(6, PlanningPanel.paletteColumnCount(12));
    }

    private static PlanningPanel panel(Move move, int apBudget) {
        return panel(move, apBudget, apBudget);
    }

    /**
     * Builds a panel with an explicit battle grid length. The grid controls the
     * on-screen bar width (it scales with tier), so input-mechanics tests that
     * click at fixed pixel coordinates pass a top-tier grid (300) to keep the
     * bar at full width; grid-sensitive tests pass their intended grid.
     */
    private static PlanningPanel panel(Move move, int apBudget, int gridLength) {
        return new PlanningPanel(
            gridLength, List.of(move), Map.of(move.getId(), 0), apBudget, 0,
            null, null, WIDTH, HEIGHT
        );
    }

    private static PlanningPanel targetedPanel(Move move) {
        return new PlanningPanel(
            300,
            "actor-1",
            List.of(new PlanningPanel.TargetOption("target-1", "Target")),
            List.of(move),
            Map.of(move.getId(), 0),
            150,
            0,
            null,
            null,
            WIDTH,
            HEIGHT
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
