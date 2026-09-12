package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline against a real forked JVM: scan, discover, instrument, gather per-test
 * coverage, seed each mutant, run the selected tests, and report.
 *
 * <p>Every expectation below is derived by hand from the fixture source, not copied from
 * jzap's own output. That is what lets these tests arbitrate when jzap and PIT disagree —
 * see docs/parity-and-benchmarks.md, Tier A.
 */
class EndToEndAnalysisTest {

    private static AnalysisResult result;

    @BeforeAll
    static void analyse() {
        result = new AnalysisEngine(new Fixture().model(Scope.all()), AnalysisEngine.Listener.SILENT)
                .analyse(null);
    }

    private static String statusOf(String className, String method, String mutator) {
        List<Mutant> matches = result.mutants().stream()
                .filter(m -> m.key().className().equals(className))
                .filter(m -> m.key().methodName().equals(method))
                .filter(m -> m.key().mutator().equals(mutator))
                .toList();
        if (matches.isEmpty()) {
            return "ABSENT";
        }
        if (matches.size() > 1) {
            return matches.stream().map(m -> m.status().name()).distinct().sorted().toList().toString();
        }
        return matches.get(0).status().name();
    }

    @Test
    void discoversTheThreeFixtureTests() {
        assertEquals(3, result.testsDiscovered());
    }

    @Test
    void baselineSuitePassesBeforeAnyMutantIsApplied() {
        assertEquals(List.of(), result.failingBaselineTests(),
                "the fixture's own tests must pass, or every verdict below is meaningless");
    }

    @Test
    void nothingFailedToAnalyse() {
        List<String> broken = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.RUN_ERROR || m.status() == MutantStatus.NON_VIABLE)
                .map(m -> m.key().asString() + " -> " + m.status())
                .toList();
        assertEquals(List.of(), broken);
    }

    @Test
    void everyMutantHasAVerdict() {
        assertFalse(result.mutants().isEmpty(), "no mutants were found at all");
        for (Mutant m : result.mutants()) {
            assertNotNull(m.status(), () -> "no verdict for " + m.key().asString());
        }
    }

    @Test
    void arithmeticAndReturnMutantsInTestedCodeAreKilled() {
        // applyPercent is asserted for two inputs, so subtraction, multiplication, division
        // and the return value are all observable.
        assertEquals("KILLED", statusOf("sample.Discount", "applyPercent", "PRIMITIVE_RETURNS"));
        assertEquals("[KILLED]", statusOf("sample.Discount", "applyPercent", "MATH"),
                "all three arithmetic operators in applyPercent are observable");
    }

    @Test
    void negatedConditionalIsKilledButItsBoundarySurvives() {
        // if (percent > 50) compiles to IF_ICMPLE. Negating it changes the result for
        // percent=10, so it dies. Moving the boundary only matters at percent=50 exactly,
        // which no test exercises.
        assertEquals("KILLED", statusOf("sample.Discount", "applyPercent", "NEGATE_CONDITIONALS"));
        assertEquals("SURVIVED", statusOf("sample.Discount", "applyPercent", "CONDITIONALS_BOUNDARY"));
    }

    @Test
    void oneSidedBooleanAssertionLetsTrueReturnsSurvive() {
        // isFree is only asserted for the true case, so forcing true is invisible while
        // forcing false is caught.
        assertEquals("SURVIVED", statusOf("sample.Discount", "isFree", "TRUE_RETURNS"));
        assertEquals("KILLED", statusOf("sample.Discount", "isFree", "FALSE_RETURNS"));
        assertEquals("KILLED", statusOf("sample.Discount", "isFree", "NEGATE_CONDITIONALS"));
    }

    @Test
    void untestedClassYieldsOnlyUncoveredMutants() {
        List<Mutant> strings = result.mutants().stream()
                .filter(m -> m.key().className().equals("sample.Strings"))
                .toList();
        assertFalse(strings.isEmpty(), "expected mutants in the untested class");
        for (Mutant m : strings) {
            assertEquals(MutantStatus.NO_COVERAGE, m.status(),
                    () -> m.key().asString() + " should have no coverage");
        }
    }

    @Test
    void uncoveredMutantsRunNoTests() {
        for (Mutant m : result.mutants()) {
            if (m.status() == MutantStatus.NO_COVERAGE) {
                assertEquals(0, m.testsRun(),
                        () -> "no test should be executed for uncovered " + m.key().asString());
            }
        }
    }

    @Test
    void earlyExitStopsAtTheFirstKillingTest() {
        List<Mutant> killed = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.KILLED)
                .filter(m -> m.coveringTests() > 1)
                .toList();
        assertFalse(killed.isEmpty(), "expected at least one killed mutant with several covering tests");
        for (Mutant m : killed) {
            assertTrue(m.testsRun() <= m.coveringTests(),
                    () -> m.key().asString() + " ran " + m.testsRun() + " of " + m.coveringTests());
            assertNotNull(m.killingTest(), () -> "no killing test recorded for " + m.key().asString());
        }
    }

    @Test
    void scoreReflectsTheDeliberateGapsInTheFixtureSuite() {
        long killed = result.count(MutantStatus.KILLED);
        long survived = result.count(MutantStatus.SURVIVED);
        long uncovered = result.count(MutantStatus.NO_COVERAGE);

        assertTrue(killed > 0 && survived > 0 && uncovered > 0,
                "the fixture is built to produce all three outcomes, got killed=" + killed
                        + " survived=" + survived + " uncovered=" + uncovered);
        assertTrue(result.testStrength() > result.mutationScore(),
                "test strength ignores uncovered mutants, so it must exceed the overall score");
    }

    @Test
    void verdictsAreReproducible() {
        AnalysisResult second =
                new AnalysisEngine(new Fixture().model(Scope.all()), AnalysisEngine.Listener.SILENT)
                        .analyse(null);

        Map<String, String> first = new LinkedHashMap<>();
        result.mutants().forEach(m -> first.put(m.key().asString(), m.status().name()));
        Map<String, String> repeat = new LinkedHashMap<>();
        second.mutants().forEach(m -> repeat.put(m.key().asString(), m.status().name()));

        assertEquals(first, repeat, "the same inputs must produce the same verdicts");
    }
}
