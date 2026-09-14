package io.github.huyz0.jzap.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down which mutants a score is a ratio over.
 *
 * <p>Every assertion here states the figure the other two choices would have produced, because
 * the failure this guards against is not an exception but a plausible-looking percentage. A
 * mutant the JVM refused, or one whose analysis broke, was never run against a test: treating it
 * as undetected blames the suite for jzap's problem, and treating it as detected (which is what
 * PIT does) credits the suite for a fault it never saw.
 */
class AnalysisScoringTest {

    private static Mutant mutant(int line, MutantStatus status) {
        return new Mutant(new MutantKey("ex.Calc", "add", "(II)I", line, "MATH", 0),
                ":app", "Calc.java", "replaced addition with subtraction",
                status, null, status == MutantStatus.NO_COVERAGE ? 0 : 2, 1, 1L);
    }

    /** Statuses in the order given; nulls stand for a mutant that was never analysed. */
    private static AnalysisResult resultOf(MutantStatus... statuses) {
        List<Mutant> mutants = new java.util.ArrayList<>();
        int line = 1;
        for (MutantStatus status : Arrays.asList(statuses)) {
            mutants.add(mutant(line++, status));
        }
        return new AnalysisResult(mutants, Map.of(), 1, "scope", "naive", List.of(), 0);
    }

    @Test
    void aRefusedOrBrokenMutantIsInNeitherHalfOfTheRatio() {
        AnalysisResult result = resultOf(MutantStatus.KILLED, MutantStatus.SURVIVED,
                MutantStatus.NON_VIABLE, MutantStatus.RUN_ERROR);

        assertEquals(2, result.scored());
        assertEquals(2, result.unscored());
        assertEquals(1, result.detected());
        // 25.0% would be counting them as undetected; 75.0% is what PIT's isDetected produces.
        assertEquals(50.0, result.mutationScore(), 0.05);
    }

    @Test
    void testStrengthExcludesThemToo() {
        AnalysisResult result = resultOf(MutantStatus.KILLED, MutantStatus.SURVIVED,
                MutantStatus.NO_COVERAGE, MutantStatus.NON_VIABLE, MutantStatus.RUN_ERROR);

        // Covered means covered and scored: not the 4 that merely have coverage recorded.
        assertEquals(2, result.covered());
        assertEquals(50.0, result.testStrength(), 0.05);
        assertEquals(33.3, result.mutationScore(), 0.05);
    }

    @Test
    void aRunThatLearnedNothingScoresZeroRatherThanOneHundred() {
        AnalysisResult result = resultOf(MutantStatus.NON_VIABLE, MutantStatus.RUN_ERROR);

        assertEquals(0, result.scored());
        assertEquals(0, result.covered());
        // An empty numerator over an empty denominator: 100.0% would claim a perfect suite from
        // a run in which no test was ever asked anything.
        assertEquals(0.0, result.mutationScore(), 0.05);
        assertEquals(0.0, result.testStrength(), 0.05);
    }

    @Test
    void aTimeoutIsStillDetectedAndStillScored() {
        AnalysisResult result = resultOf(MutantStatus.TIMED_OUT, MutantStatus.SURVIVED);

        assertEquals(2, result.scored());
        assertEquals(0, result.unscored());
        assertEquals(50.0, result.mutationScore(), 0.05);
    }

    @Test
    void aMutantNeverAnalysedIsNotCountedAgainstTheSuite() {
        AnalysisResult result = resultOf(MutantStatus.KILLED, null);

        assertEquals(1, result.scored());
        assertEquals(1, result.unscored());
        // 50.0% would be reading "not analysed yet" as "survived".
        assertEquals(100.0, result.mutationScore(), 0.05);
    }
}
