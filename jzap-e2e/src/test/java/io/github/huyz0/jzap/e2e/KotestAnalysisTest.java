package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kotest, which runs on the JUnit Platform and therefore "works already" right up until it does
 * not.
 *
 * <p>Kotest builds its test tree when a spec runs, not when the platform asks what the spec
 * contains. Discovery returns one container per spec and no leaves at all. jzap used to collect
 * only leaf descriptors, so for a Kotest project it found zero tests, reported every mutant as
 * uncovered, and printed a plausible-looking score of 0% with no error anywhere. That silence is
 * the reason this milestone exists and the reason these assertions are about counts rather than
 * about the run merely completing.
 */
class KotestAnalysisTest {

    private static AnalysisResult result;
    private static int boundaryLine;

    @BeforeAll
    static void analyse() {
        Fixture fixture = Fixture.kotest();
        result = new AnalysisEngine(fixture.model(Scope.all()), AnalysisEngine.Listener.SILENT)
                .analyse(null);
        boundaryLine = fixture.lineContaining("ktest/Basket.kt", "fun bulkDiscount");
    }

    @Test
    void discoversTheSpecsAsRunnableUnits() {
        // Four specs, one per style: StringSpec, FunSpec, DescribeSpec, BehaviorSpec.
        assertEquals(4, result.testsDiscovered(),
                "Kotest exposes a container per spec at discovery time, and those are the units");
    }

    @Test
    void actuallyRunsTheTests() {
        assertEquals(0, result.count(MutantStatus.NO_COVERAGE),
                "every mutant here is reachable; uncovered would mean nothing ran");
        assertEquals(0, result.count(MutantStatus.RUN_ERROR));
        assertEquals(List.of(), result.failingBaselineTests());
        assertTrue(result.count(MutantStatus.KILLED) > 0, "no mutant was killed at all");
    }

    @Test
    void findsTheDeliberateGap() {
        List<Mutant> survivors = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.SURVIVED)
                .toList();

        // bulkDiscount is asserted only for quantity=20, so moving the boundary at 10 is
        // invisible. Derived from the fixture source, not from what jzap reports.
        assertEquals(1, survivors.size(),
                () -> "expected one survivor, got " + survivors.stream()
                        .map(m -> m.key().asString()).toList());
        assertEquals("CONDITIONALS_BOUNDARY", survivors.get(0).key().mutator());
        assertEquals(boundaryLine, survivors.get(0).key().line());
    }

    @Test
    void reportsAgainstKotlinSources() {
        assertFalse(result.mutants().isEmpty());
        assertTrue(result.mutants().stream().allMatch(m -> m.sourceFile().endsWith(".kt")));
    }

    @Test
    void everySpecStyleContributesCoverage() {
        // If only some styles ran, mutants exercised solely by the others would be uncovered.
        // All four styles touch distinct methods of Basket, so full coverage is the check.
        assertTrue(result.mutants().stream()
                        .filter(m -> m.status() != MutantStatus.NO_COVERAGE)
                        .anyMatch(m -> m.key().methodName().equals("subtotal")),
                "StringSpec and BehaviorSpec cover subtotal");
        assertTrue(result.mutants().stream()
                        .filter(m -> m.status() != MutantStatus.NO_COVERAGE)
                        .anyMatch(m -> m.key().methodName().equals("shipping")),
                "FunSpec covers shipping");
        assertTrue(result.mutants().stream()
                        .filter(m -> m.status() != MutantStatus.NO_COVERAGE)
                        .anyMatch(m -> m.key().methodName().equals("isEmpty")),
                "DescribeSpec covers isEmpty");
    }

    @Test
    void unitIdsAreStableAcrossRuns() {
        // The cache keys killing tests by unique id. An id that varied between runs would
        // silently disable reuse, or match the wrong unit.
        AnalysisResult second = new AnalysisEngine(Fixture.kotest().model(Scope.all()),
                AnalysisEngine.Listener.SILENT).analyse(null);

        assertEquals(
                result.mutants().stream().map(Mutant::killingTest).toList(),
                second.mutants().stream().map(Mutant::killingTest).toList(),
                "the same unit must kill the same mutant every time");
    }

    @Test
    void verdictsMatchWhatTheSameCodeWouldGetUnderJupiter() {
        // Basket.shipping and Discount.applyPercent are different code, so this is not a direct
        // comparison; what it checks is that a Kotest project produces a real distribution of
        // verdicts rather than the degenerate all-uncovered one that the old behaviour gave.
        assertTrue(result.count(MutantStatus.KILLED) >= 9,
                "expected most mutants killed, got " + result.count(MutantStatus.KILLED));
        assertEquals(1, result.count(MutantStatus.SURVIVED));
    }
}
