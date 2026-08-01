package com.jjktbf.model.progression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.CharacterStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TechniqueMasteryProgressionDataTest {

    @Test
    void appliesStandardPrecedenceParenthesesAndUnaryOperators() {
        assertEquals(14, formula("2 + 3 * 4").resolve(0));
        assertEquals(20, formula("(2 + 3) * 4").resolve(0));
        assertEquals(-3, formula("-5 + +2").resolve(0));
    }

    @Test
    void supportsFunctionsWhitespaceAndCaseInsensitiveNames() {
        TechniqueMasteryProgressionData data = formula(
            "  MaX( 2, mIn( CTm / 2, 20 ) )  ");

        assertEquals(2, data.resolve(2));
        assertEquals(15, data.resolve(30));
        assertEquals(20, data.resolve(100));
    }

    @Test
    void floorsDecimalResultsMathematicallyIncludingNegatives() {
        assertEquals(1, formula("ctm / 2").resolve(3));
        assertEquals(-2, formula("-ctm / 2").resolve(3));
        assertEquals(-1, formula("-1 / 3").resolve(0));
    }

    @Test
    void clampsFunctionValuesAndCtmInput() {
        TechniqueMasteryProgressionData clamped = formula("clamp(ctm * 2, 10, 50)");
        assertEquals(10, clamped.resolve(2));
        assertEquals(40, clamped.resolve(20));
        assertEquals(50, clamped.resolve(100));

        TechniqueMasteryProgressionData ctm = formula("ctm");
        assertEquals(0, ctm.resolve(-100));
        assertEquals(300, ctm.resolve(500));
    }

    @Test
    void characterStatsPreserveTheZeroMasterySentinel() {
        assertEquals(0, new CharacterStats.Builder().cursedTechniqueMastery(0)
            .build().getCursedTechniqueMastery());
        assertEquals(CharacterStats.MIN_STAT,
            new CharacterStats.Builder().cursedTechniqueMastery(1)
                .build().getCursedTechniqueMastery());
    }

    @Test
    void benchmarkModeUsesHighestReachedThresholdAndClampsCtm() {
        TechniqueMasteryProgressionData data = benchmarks(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 5),
            new TechniqueMasteryProgressionData.BenchmarkData(50, 12),
            new TechniqueMasteryProgressionData.BenchmarkData(200, 40));

        assertNull(data.validationError());
        assertEquals(5, data.resolve(-1));
        assertEquals(5, data.resolve(49));
        assertEquals(12, data.resolve(50));
        assertEquals(12, data.resolve(199));
        assertEquals(40, data.resolve(200));
        assertEquals(40, data.resolve(999));
    }

    @Test
    void copyIsDeepAndPreservesSourceValues() {
        TechniqueMasteryProgressionData source = benchmarks(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 1),
            new TechniqueMasteryProgressionData.BenchmarkData(100, 9));

        TechniqueMasteryProgressionData copy = source.copy();
        copy.mode = TechniqueMasteryProgressionData.FORMULA;
        copy.formula = "7";
        copy.benchmarks.get(0).value = 99;
        copy.benchmarks.add(new TechniqueMasteryProgressionData.BenchmarkData(200, 20));

        assertNotSame(source.benchmarks, copy.benchmarks);
        assertNotSame(source.benchmarks.get(0), copy.benchmarks.get(0));
        assertEquals(TechniqueMasteryProgressionData.BENCHMARKS, source.mode);
        assertEquals(1, source.benchmarks.get(0).value);
        assertEquals(2, source.benchmarks.size());
    }

    @Test
    void jsonRoundTripsFormulaAndBenchmarkDtos() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TechniqueMasteryProgressionData source = benchmarks(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 3),
            new TechniqueMasteryProgressionData.BenchmarkData(80, 11));

        TechniqueMasteryProgressionData restored = mapper.readValue(
            mapper.writeValueAsString(source), TechniqueMasteryProgressionData.class);

        assertEquals(TechniqueMasteryProgressionData.BENCHMARKS, restored.mode);
        assertEquals(80, restored.benchmarks.get(1).mastery);
        assertEquals(11, restored.resolve(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1 +", "(1 + 2", "1 2", "1.5", "min(1)", "clamp(1, 2)", "ctm(1)", "@"
    })
    void rejectsMalformedSyntax(String expression) {
        assertNotNull(formula(expression).validationError(), expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other + 1", "average(1, 2)", "NaN", "Infinity"})
    void rejectsUnknownIdentifiersFunctionsAndNonFiniteNames(String expression) {
        assertNotNull(formula(expression).validationError(), expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 / 0", "1 % 0", "10 / (ctm - 17)", "10 % (ctm - 250)"})
    void rejectsDivisionOrModuloByZeroAtAnyCtm(String expression) {
        assertNotNull(formula(expression).validationError(), expression);
    }

    @Test
    void rejectsResultsOutsideJavaIntRange() {
        assertNotNull(formula("2147483648").validationError());
        assertNotNull(formula("-2147483649").validationError());
        assertNull(formula("2147483647").validationError());
        assertNull(formula("-2147483648").validationError());
    }

    @Test
    void rejectsExcessiveFormulaLengthAndNestingDepth() {
        assertNotNull(formula("1".repeat(TechniqueMasteryFormula.MAX_LENGTH + 1)).validationError());
        String nested = "(".repeat(TechniqueMasteryFormula.MAX_DEPTH + 1)
            + "1" + ")".repeat(TechniqueMasteryFormula.MAX_DEPTH + 1);
        assertNotNull(formula(nested).validationError());
    }

    @Test
    void validatesBenchmarkShapeAndMode() {
        TechniqueMasteryProgressionData empty = benchmarks();
        assertNotNull(empty.validationError());

        TechniqueMasteryProgressionData missingZero = benchmarks(
            new TechniqueMasteryProgressionData.BenchmarkData(1, 10));
        assertNotNull(missingZero.validationError());

        TechniqueMasteryProgressionData duplicate = benchmarks(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 1),
            new TechniqueMasteryProgressionData.BenchmarkData(0, 2));
        assertNotNull(duplicate.validationError());

        TechniqueMasteryProgressionData outOfRange = benchmarks(
            new TechniqueMasteryProgressionData.BenchmarkData(0, 1),
            new TechniqueMasteryProgressionData.BenchmarkData(301, 2));
        assertNotNull(outOfRange.validationError());

        TechniqueMasteryProgressionData unknown = new TechniqueMasteryProgressionData();
        unknown.mode = "UNKNOWN";
        assertNotNull(unknown.validationError());
        assertThrows(IllegalStateException.class, () -> unknown.resolve(10));
    }

    private static TechniqueMasteryProgressionData formula(String expression) {
        TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
        data.mode = TechniqueMasteryProgressionData.FORMULA;
        data.formula = expression;
        return data;
    }

    private static TechniqueMasteryProgressionData benchmarks(
        TechniqueMasteryProgressionData.BenchmarkData... entries
    ) {
        TechniqueMasteryProgressionData data = new TechniqueMasteryProgressionData();
        data.mode = TechniqueMasteryProgressionData.BENCHMARKS;
        data.benchmarks = new ArrayList<>(List.of(entries));
        return data;
    }
}
