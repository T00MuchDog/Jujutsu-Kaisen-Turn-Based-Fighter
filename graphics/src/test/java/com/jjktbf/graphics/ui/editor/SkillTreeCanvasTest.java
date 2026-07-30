package com.jjktbf.graphics.ui.editor;

import com.jjktbf.model.character.StatKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillTreeCanvasTest {

    @Test
    void missingStatPrerequisiteDefaultsToVitality() {
        assertEquals(StatKey.VITALITY, SkillTreeCanvas.statOf(null));
        assertEquals(StatKey.VITALITY, SkillTreeCanvas.statOf(""));
    }
}
