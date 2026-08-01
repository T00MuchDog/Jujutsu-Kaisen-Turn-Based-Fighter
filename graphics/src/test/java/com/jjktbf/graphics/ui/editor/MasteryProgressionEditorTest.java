package com.jjktbf.graphics.ui.editor;

import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MasteryProgressionEditorTest {

    @Test
    void previewShowsEveryTwentyPointsThroughThreeHundred() {
        TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
        data.mode = TechniqueMasteryProgressionData.BENCHMARKS;
        data.benchmarks = List.of(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 1),
            new TechniqueMasteryProgressionData.BenchmarkData(200, 2));

        String preview = MasteryProgressionEditor.previewText(data);

        for (int mastery = 0; mastery <= 300; mastery += 20) {
            assertTrue(preview.contains(mastery + ": "), preview);
        }
        assertTrue(preview.endsWith("300: 2"), preview);
    }

    @Test
    void previewSurfacesFormulaErrors() {
        TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
        data.mode = TechniqueMasteryProgressionData.FORMULA;
        data.formula = "10 / (ctm - 20)";

        assertTrue(MasteryProgressionEditor.previewText(data).startsWith("Invalid progression:"));
    }
}
