package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.report.NativeJsonReporter;
import io.github.huyz0.jzap.report.ReportContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking for more threads must change how long a run takes and nothing else.
 *
 * <p>Thread count leaking into a verdict or into report bytes would break the Gradle build
 * cache before it is even built, so both are asserted rather than assumed.
 *
 * <h2>What this fixture can and cannot show</h2>
 *
 * The engine caps workers at what the work justifies, because starting an analysis JVM costs
 * about a quarter of a second and starts it cold. This fixture is a few tens of milliseconds of
 * work, so asking for eight threads here yields one worker -- which means these tests prove that
 * the request is honoured without changing the answer, not that eight JVMs produce the same
 * answer as one. {@code workersActuallyUsed} asserts that plainly rather than leaving the
 * distinction to be rediscovered.
 *
 * <p>The rule that decides the count is checked at realistic scales in
 * {@code MutantExecutor.workerCountFor}'s own test, and genuinely parallel runs are measured by
 * the bench harness, whose S1b scenario runs the 1080-mutant fixture at 1, 2, 4 and 20 threads.
 */
class ParallelExecutionTest {

    private static Map<String, String> verdictsWith(int threads) {
        AnalysisResult result = new AnalysisEngine(
                new Fixture().model(Scope.all()).withThreads(threads),
                AnalysisEngine.Listener.SILENT).analyse(null);
        Map<String, String> verdicts = new LinkedHashMap<>();
        result.mutants().forEach(m -> verdicts.put(m.key().asString(), m.status().name()));
        return verdicts;
    }

    @Test
    void workersActuallyUsedAreCappedByTheWorkThisFixtureHas() {
        AnalysisResult result = new AnalysisEngine(
                new Fixture().model(Scope.all()).withThreads(8),
                AnalysisEngine.Listener.SILENT).analyse(null);

        assertEquals(1L, result.timings().get("executionMinionsStarted"),
                "this fixture is too small to justify a second analysis JVM, so the tests below "
                        + "compare capped runs; see the class comment");
    }

    @Test
    void verdictsAreIdenticalAtEveryThreadCount() {
        Map<String, String> single = verdictsWith(1);
        assertFalse(single.isEmpty());

        assertEquals(single, verdictsWith(2), "two threads changed a verdict");
        assertEquals(single, verdictsWith(8), "eight threads changed a verdict");
    }

    @Test
    void reportBytesAreIdenticalAtEveryThreadCount(@TempDir Path dir) throws Exception {
        Path oneThread = dir.resolve("one");
        Path manyThreads = dir.resolve("many");

        for (Map.Entry<Path, Integer> run : Map.of(oneThread, 1, manyThreads, 8).entrySet()) {
            AnalysisResult result = new AnalysisEngine(
                    new Fixture().model(Scope.all()).withThreads(run.getValue()),
                    AnalysisEngine.Listener.SILENT).analyse(null);
            new NativeJsonReporter().write(result, ReportContext.of(run.getKey(), List.of()));
        }

        // Timings legitimately differ, so they are excluded; everything else must match byte
        // for byte, which is what the build cache will depend on.
        assertEquals(withoutTimings(oneThread), withoutTimings(manyThreads));
    }

    private static String withoutTimings(Path reportDir) throws Exception {
        String json = Files.readString(reportDir.resolve(NativeJsonReporter.FILE_NAME));
        return json.substring(0, json.indexOf("\"timings\""));
    }

    @Test
    void aHangingMutantKillsOnlyItsOwnWorker() {
        // hang.Countdown has one mutant that loops forever (n - 1 becomes n + 1) and several
        // that do not. The rest of the class must still be analysed after the hang.
        AnalysisResult result = new AnalysisEngine(
                Fixture.hang().model(Scope.all()).withThreads(2),
                AnalysisEngine.Listener.SILENT).analyse(null);

        List<Mutant> timedOut = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.TIMED_OUT)
                .toList();
        assertEquals(1, timedOut.size(),
                () -> "expected exactly one hanging mutant, got "
                        + result.mutants().stream()
                                .map(m -> m.key().asString() + "=" + m.status()).toList());
        assertEquals("MATH", timedOut.get(0).key().mutator());
        assertEquals("countdown", timedOut.get(0).key().methodName());

        assertTrue(result.count(MutantStatus.KILLED) > 0,
                "the worker must recover and keep analysing after killing the hung JVM");
        assertEquals(0, result.count(MutantStatus.RUN_ERROR),
                "a hang is not a run error; it has its own status because it is not reproducible");
    }

    @Test
    void timedOutIsNotTreatedAsACacheableVerdict() {
        // Stated as a test because M11's cache will depend on it: a wall-clock timeout is not
        // reproducible, so reusing it later would be reporting a guess as a result.
        assertFalse(MutantStatus.TIMED_OUT.isCacheable());
        assertTrue(MutantStatus.TIMED_OUT.isDetected(),
                "a mutant that hangs the suite is still detected by it");
    }
}
