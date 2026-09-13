package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
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
 * A project whose tests are already failing.
 *
 * <p>Every verdict in such a project is suspect: a mutant covered by a test that fails anyway
 * looks killed by a failure that has nothing to do with it. jzap detects those tests, excludes
 * them from selection, and says so.
 *
 * <p>This test exists because that detection was silently not working. The minion reported a
 * failing test as passing whenever it was asked to run tests without stopping at the first failure
 * -- which is exactly what the coverage phase does. Nothing failed; the warning simply never
 * appeared, and the exclusion never happened.
 */
class RedSuiteTest {

    private static AnalysisResult result;

    @BeforeAll
    static void analyse() {
        result = new AnalysisEngine(Fixture.red().model(Scope.all()),
                AnalysisEngine.Listener.SILENT).analyse(null);
    }

    @Test
    void theFailingTestIsReported() {
        assertEquals(1, result.failingBaselineTests().size(),
                () -> "expected one already-failing test, got " + result.failingBaselineTests());
        assertTrue(result.failingBaselineTests().get(0).contains("subtractsIncorrectlyOnPurpose"),
                result.failingBaselineTests().get(0));
    }

    @Test
    void mutantsCoveredOnlyByTheFailingTestAreNotCountedAsKilled() {
        // subtract() is covered by nothing but the already-failing test. Counting those mutants as
        // killed would report a passing grade earned entirely by a broken test.
        List<Mutant> subtractMutants = result.mutants().stream()
                .filter(m -> m.key().methodName().equals("subtract"))
                .toList();

        assertFalse(subtractMutants.isEmpty());
        assertTrue(subtractMutants.stream().allMatch(m -> m.status() == MutantStatus.NO_COVERAGE),
                () -> "expected these to be excluded from selection, got " + subtractMutants.stream()
                        .map(m -> m.key().asString() + "=" + m.status()).toList());
    }

    @Test
    void theRestOfTheProjectIsStillAnalysed() {
        List<Mutant> addMutants = result.mutants().stream()
                .filter(m -> m.key().methodName().equals("add"))
                .toList();

        assertFalse(addMutants.isEmpty());
        assertTrue(addMutants.stream().anyMatch(m -> m.status() == MutantStatus.KILLED),
                "a red test elsewhere must not stop the rest of the analysis");
    }
}
