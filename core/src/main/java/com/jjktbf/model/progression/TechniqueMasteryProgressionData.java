package com.jjktbf.model.progression;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Persisted formula or step-based progression driven by cursed technique mastery. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TechniqueMasteryProgressionData {

    public static final String FORMULA = "FORMULA";
    public static final String BENCHMARKS = "BENCHMARKS";

    public String mode;
    public String formula;
    public List<BenchmarkData> benchmarks;
    private transient String cachedFormula;
    private transient TechniqueMasteryFormula.Expression cachedExpression;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BenchmarkData {
        public int mastery;
        public int value;

        public BenchmarkData() {
        }

        public BenchmarkData(int mastery, int value) {
            this.mastery = mastery;
            this.value = value;
        }

        public BenchmarkData copy() {
            return new BenchmarkData(mastery, value);
        }
    }

    public TechniqueMasteryProgressionData copy() {
        TechniqueMasteryProgressionData copy = new TechniqueMasteryProgressionData();
        copy.mode = mode;
        copy.formula = formula;
        if (benchmarks != null) {
            copy.benchmarks = new ArrayList<>(benchmarks.size());
            for (BenchmarkData benchmark : benchmarks) {
                copy.benchmarks.add(benchmark == null ? null : benchmark.copy());
            }
        }
        return copy;
    }

    /** Returns {@code null} when valid, otherwise a deterministic description. */
    public String validationError() {
        if (mode == null) return "Progression mode is required";
        if (FORMULA.equals(mode)) return formulaValidationError();
        if (BENCHMARKS.equals(mode)) return benchmarkValidationError();
        return "Progression mode must be FORMULA or BENCHMARKS";
    }

    /** Resolves the progression after clamping CTM to the authored 0..300 range. */
    public int resolve(int ctm) {
        int clampedCtm = Math.max(0, Math.min(300, ctm));
        if (FORMULA.equals(mode)) {
            if (formula == null || formula.isBlank()) {
                throw new IllegalStateException("Invalid technique mastery progression: Formula is required");
            }
            return expression()
                .evaluate(TechniqueMasteryFormula.Rational.of(clampedCtm))
                .floorToInt();
        }
        if (!BENCHMARKS.equals(mode)) {
            throw new IllegalStateException(
                "Invalid technique mastery progression: unknown mode " + mode);
        }
        String benchmarkError = benchmarkValidationError();
        if (benchmarkError != null) {
            throw new IllegalStateException(
                "Invalid technique mastery progression: " + benchmarkError);
        }

        int value = benchmarks.get(0).value;
        for (BenchmarkData benchmark : benchmarks) {
            if (benchmark.mastery > clampedCtm) break;
            value = benchmark.value;
        }
        return value;
    }

    private String formulaValidationError() {
        if (formula == null || formula.isBlank()) return "Formula is required";

        TechniqueMasteryFormula.Expression expression;
        try {
            expression = expression();
        } catch (TechniqueMasteryFormula.FormulaException exception) {
            return "Invalid formula: " + exception.getMessage();
        }

        for (int ctm = 0; ctm <= 300; ctm++) {
            try {
                expression.evaluate(TechniqueMasteryFormula.Rational.of(ctm)).floorToInt();
            } catch (TechniqueMasteryFormula.FormulaException exception) {
                return "Invalid formula at CTM " + ctm + ": " + exception.getMessage();
            }
        }
        return null;
    }

    private TechniqueMasteryFormula.Expression expression() {
        if (cachedExpression == null || !java.util.Objects.equals(cachedFormula, formula)) {
            cachedExpression = TechniqueMasteryFormula.parse(formula);
            cachedFormula = formula;
        }
        return cachedExpression;
    }

    private String benchmarkValidationError() {
        if (benchmarks == null || benchmarks.isEmpty()) {
            return "At least one benchmark is required";
        }
        if (benchmarks.get(0) == null) return "Benchmark 1 is required";
        if (benchmarks.get(0).mastery != 0) return "First benchmark mastery must be 0";

        int previousMastery = -1;
        for (int index = 0; index < benchmarks.size(); index++) {
            BenchmarkData benchmark = benchmarks.get(index);
            if (benchmark == null) return "Benchmark " + (index + 1) + " is required";
            if (benchmark.mastery < 0 || benchmark.mastery > 300) {
                return "Benchmark " + (index + 1) + " mastery must be between 0 and 300";
            }
            if (benchmark.mastery <= previousMastery) {
                return "Benchmark masteries must be strictly increasing";
            }
            previousMastery = benchmark.mastery;
        }
        return null;
    }
}
