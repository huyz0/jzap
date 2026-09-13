package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analysis that really does run in several JVMs at once.
 *
 * <p>ParallelExecutionTest asks for more threads on the sample fixture, which is a few tens of
 * milliseconds of work: the engine caps that to one worker, so what it proves is that the request
 * is honoured without changing the answer. This fixture is slow enough that the cap lets more than
 * one JVM start, so the concurrent path is the one under test -- the shared mutant queue, several
 * workers each owning their own minions, and results merged from all of them.
 *
 * <p>What must hold is that concurrency changes the duration and nothing else. A verdict that
 * depended on the worker count would make every report unreproducible, and the Gradle build cache
 * unsound before it is even built.
 */
class ConcurrentExecutionTest {

    private static AnalysisResult analyse(int threads) {
        return new AnalysisEngine(
                Fixture.parallel().model(Scope.all()).withThreads(threads),
                AnalysisEngine.Listener.SILENT).analyse(null);
    }

    private static Map<String, String> verdicts(AnalysisResult result) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        result.mutants().forEach(m -> verdicts.put(m.key().asString(), m.status().name()));
        return verdicts;
    }

    @Test
    void severalWorkersReallyStartOnThisFixture() {
        AnalysisResult result = analyse(4);

        assertTrue(result.timings().get("executionMinionsStarted") > 1,
                "this fixture exists to get past the worker cap; if it no longer does, the "
                        + "concurrent path is untested. Minions started: "
                        + result.timings().get("executionMinionsStarted"));
    }

    @Test
    void verdictsAreTheSameWhetherOneWorkerOrSeveral() {
        Map<String, String> single = verdicts(analyse(1));
        Map<String, String> concurrent = verdicts(analyse(4));

        assertFalse(single.isEmpty());
        assertEquals(single, concurrent,
                "a verdict that depends on the worker count makes every report unreproducible");
    }

    @Test
    void everyMutantIsAccountedForByExactlyOneWorker() {
        AnalysisResult single = analyse(1);
        AnalysisResult concurrent = analyse(4);

        assertEquals(single.mutants().size(), concurrent.mutants().size(),
                "a mutant dropped between workers would quietly raise the score, and one "
                        + "counted twice would quietly lower it");
        assertEquals(single.mutants().stream().map(m -> m.key().asString()).toList(),
                concurrent.mutants().stream().map(m -> m.key().asString()).toList(),
                "and the order has to be stable, since report bytes depend on it");
    }

    @Test
    void theSuiteIsStrongEnoughForThisToBeMeaningful() {
        AnalysisResult result = analyse(4);

        assertTrue(result.count(MutantStatus.KILLED) > 0,
                "if nothing were killed, equal verdicts would prove nothing");
        assertEquals(0, result.count(MutantStatus.RUN_ERROR),
                "a run error under concurrency is the failure this test exists to catch: "
                        + result.mutants().stream()
                                .filter(m -> m.status() == MutantStatus.RUN_ERROR).toList());
    }
}
