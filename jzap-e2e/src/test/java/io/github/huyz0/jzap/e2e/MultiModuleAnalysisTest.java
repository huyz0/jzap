package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.report.SourceLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library module with no tests of its own, and an application module whose tests exercise it.
 *
 * <p>This is the ordinary shape of a multi-module project and the case that single-module
 * mutation testing cannot see: analysed module by module, every mutant in the library reports as
 * uncovered and the score is meaningless. PIT supports the cross-module case only partially and
 * only with explicit configuration; jzap analyses every module in one pass so it falls out.
 */
class MultiModuleAnalysisTest {

    private static AnalysisResult result;

    @BeforeAll
    static void analyse() {
        result = new AnalysisEngine(Fixture.multiModule(), AnalysisEngine.Listener.SILENT)
                .analyse(null);
    }

    private static List<Mutant> mutantsIn(String packageName) {
        return result.mutants().stream()
                .filter(m -> m.key().className().startsWith(packageName + "."))
                .toList();
    }

    @Test
    void analysesBothModulesInOneRun() {
        assertFalse(mutantsIn("mcore").isEmpty(), "the library module produced no mutants");
        assertFalse(mutantsIn("mapp").isEmpty(), "the application module produced no mutants");
        assertEquals(0, result.count(MutantStatus.RUN_ERROR));
        assertEquals(List.of(), result.failingBaselineTests());
    }

    @Test
    void testsInOneModuleKillMutantsInAnother() {
        List<Mutant> libraryMutants = mutantsIn("mcore");
        long killed = libraryMutants.stream()
                .filter(m -> m.status() == MutantStatus.KILLED)
                .count();

        // mcore has no test sources at all. Analysed a module at a time, every one of these would
        // be NO_COVERAGE.
        assertTrue(killed > 0,
                () -> "no library mutant was killed: " + libraryMutants.stream()
                        .map(m -> m.key().asString() + "=" + m.status()).toList());
        assertTrue(libraryMutants.stream()
                        .filter(m -> m.status() == MutantStatus.KILLED)
                        .allMatch(m -> m.killingTest().contains("mapp.InvoiceTest")),
                "the killing tests all live in the other module");
    }

    @Test
    void everyKillingTestComesFromTheModuleThatHasTests() {
        Map<String, Long> byTestClass = result.mutants().stream()
                .filter(m -> m.killingTest() != null)
                .collect(Collectors.groupingBy(
                        m -> m.killingTest().split("\\[class:")[1].split("]")[0],
                        Collectors.counting()));

        assertEquals(List.of("mapp.InvoiceTest"), List.copyOf(byTestClass.keySet()),
                "only one module has tests, so every kill must be attributed to it");
    }

    @Test
    void coverageIsGatheredOncePerTestBearingModule() {
        // multi-core declares no test class paths, so it contributes no coverage run at all.
        // Three tests, all from multi-app.
        assertEquals(3, result.testsDiscovered());
    }

    @Test
    void anUncoveredLibraryMutantIsStillReportedHonestly() {
        List<Mutant> uncovered = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.NO_COVERAGE)
                .toList();

        // roundUpTo's early return for a non-positive step is never exercised. Cross-module
        // selection must not paper over a genuine gap.
        assertEquals(1, uncovered.size(),
                () -> "expected exactly one uncovered mutant, got " + uncovered.stream()
                        .map(m -> m.key().asString()).toList());
        assertTrue(uncovered.get(0).key().className().startsWith("mcore."));
    }

    @Test
    void survivorsInTheLibraryAreReportedAgainstItsOwnSource() {
        List<Mutant> survivors = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.SURVIVED)
                .toList();

        assertFalse(survivors.isEmpty());
        // Checked through the same helper the reporters use, since that is the path a user sees;
        // Mutant.sourceFile is the bare name from the class file's SourceFile attribute.
        assertTrue(survivors.stream()
                        .allMatch(m -> SourceLocator.relativePath(m).startsWith("mcore/")),
                "a mutant belongs to the module whose source it is in, not the one that tested it: "
                        + survivors.stream().map(SourceLocator::relativePath).toList());
    }

    @Test
    void parallelExecutionDoesNotChangeAnyVerdict() {
        AnalysisResult parallel = new AnalysisEngine(Fixture.multiModule().withThreads(4),
                AnalysisEngine.Listener.SILENT).analyse(null);

        assertEquals(
                result.mutants().stream().map(m -> m.key().asString() + "=" + m.status()).toList(),
                parallel.mutants().stream().map(m -> m.key().asString() + "=" + m.status()).toList());
    }
}
