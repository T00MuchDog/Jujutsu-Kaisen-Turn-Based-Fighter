package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.ui.profile.BattleUiLayout;
import com.jjktbf.graphics.ui.profile.UiProfile;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningPanelLayoutTest {

    @Test
    void windows1440pKeepsSixCardPaletteMiraclesAndTimelinesDisjoint() {
        assertWindowsLayoutFits(2560f, 1440f, false);
    }

    @Test
    void windows720pUsesShortScrollableLayoutWithoutOverlap() {
        assertWindowsLayoutFits(1280f, 720f, true);
    }

    private static void assertWindowsLayoutFits(
        float width,
        float height,
        boolean expectedShortViewport
    ) {
        List<Move> moves = IntStream.range(0, 6)
            .mapToObj(PlanningPanelLayoutTest::move)
            .toList();
        Map<String, Integer> costs = moves.stream()
            .collect(Collectors.toMap(Move::getId, ignored -> 0));
        CodedAbilityState miracles = new CodedAbilityState(
            MiraclesAbility.KEY, "Miracles", 6, 6);
        PlanningPanel panel = new PlanningPanel(
            300, moves, costs, 150, 0, 100, miracles, null, width, height);
        panel.setActorName("Windows Layout Fighter");
        panel.setLayout(BattleUiLayout.defaults(UiProfile.WINDOWS));

        PlanningPanel.LayoutSnapshot snapshot = panel.layoutSnapshot();
        Rectangle palette = snapshot.palette();
        Rectangle defense = snapshot.defensiveTimeline();
        Rectangle offense = snapshot.offensiveTimeline();
        Rectangle meter = snapshot.miracles();
        Rectangle header = snapshot.header();

        assertTrue(palette.y + palette.height <= defense.y);
        assertTrue(defense.y + defense.height <= offense.y);
        assertTrue(offense.y + offense.height <= header.y);
        assertFalse(meter.overlaps(palette));
        assertFalse(meter.overlaps(defense));
        assertFalse(meter.overlaps(offense));
        assertFalse(meter.overlaps(header));
        assertInsideViewport(palette, width, height);
        assertInsideViewport(defense, width, height);
        assertInsideViewport(offense, width, height);
        assertInsideViewport(meter, width, height);
        assertInsideViewport(header, width, height);
        assertTrue(snapshot.paletteScrollMaximum() > 0f);
        assertTrue(snapshot.shortViewport() == expectedShortViewport);
    }

    private static void assertInsideViewport(Rectangle bounds, float width, float height) {
        assertTrue(bounds.x >= 0f);
        assertTrue(bounds.y >= 0f);
        assertTrue(bounds.x + bounds.width <= width);
        assertTrue(bounds.y + bounds.height <= height);
    }

    private static Move move(int index) {
        MoveData data = new MoveData();
        data.id = "LAYOUT_MOVE_" + index;
        data.name = "Layout Move " + index;
        data.description = "A move used to verify planner layout bands.";
        data.tags = List.of("ATTACK", "PHYSICAL");
        data.apCost = 10;
        data.unleashPoint = 1;
        return data.toMove();
    }
}
