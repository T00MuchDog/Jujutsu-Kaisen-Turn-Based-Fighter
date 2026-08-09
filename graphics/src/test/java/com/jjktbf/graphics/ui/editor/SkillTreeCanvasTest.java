package com.jjktbf.graphics.ui.editor;

import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.technique.SkillTreeNodeData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillTreeCanvasTest {

    @Test
    void missingStatPrerequisiteDefaultsToVitality() {
        assertEquals(StatKey.VITALITY, SkillTreeCanvas.statOf(null));
        assertEquals(StatKey.VITALITY, SkillTreeCanvas.statOf(""));
    }

    @Test
    void verticalBoundsKeepAConstantMarginAroundOuterNodes() {
        SkillTreeNodeData lower = nodeAt(-100f);
        SkillTreeNodeData upper = nodeAt(700f);

        SkillTreeCanvas.VerticalBounds bounds = SkillTreeCanvas.verticalBoundsFor(
            List.of(lower, upper));

        assertEquals(60f, bounds.canvasY(lower.y), 0.001f);
        assertEquals(60f, bounds.preferredHeight() - (bounds.canvasY(upper.y) + 92.8f), 0.001f);
    }

    @Test
    void placingANodeAtTheCurrentEdgeExtendsTheAvailableSpaceByOneMargin() {
        SkillTreeNodeData lower = nodeAt(0f);
        SkillTreeNodeData upper = nodeAt(700f);
        SkillTreeCanvas.VerticalBounds initial = SkillTreeCanvas.verticalBoundsFor(
            List.of(lower, upper));

        upper.y = initial.clampNodeY(Float.MAX_VALUE);
        SkillTreeCanvas.VerticalBounds expanded = SkillTreeCanvas.verticalBoundsFor(
            List.of(lower, upper));

        assertEquals(initial.topY() + 60f, expanded.topY(), 0.001f);
    }

    private static SkillTreeNodeData nodeAt(float y) {
        SkillTreeNodeData node = new SkillTreeNodeData();
        node.y = y;
        return node;
    }
}
