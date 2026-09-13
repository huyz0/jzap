package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mutant that blocks rather than loops, which only the wall clock can catch.
 *
 * <p>Runaway mutants are normally detected by counting loop back edges, because that gives the
 * same verdict on every machine however loaded it is -- HangDetectionTest covers that. Blocking
 * executes no back edges, so the counter never trips, and the read timeout is the only thing left.
 * This is the one place where a jzap verdict depends on the clock, and it is documented as such;
 * until now it was also the one place with no test.
 *
 * <p>What has to hold is that the analysis survives it. The mutant's JVM is killed -- the only way
 * to stop a blocked thread -- and the run has to carry on and report the mutant rather than
 * hanging with it.
 */
class WallClockBackstopTest {

    /**
     * @param timeoutConstMillis kept short so the test costs a few seconds rather than the
     *                           four the default would spend waiting
     */
    private static ProjectModel model(int timeoutConstMillis) {
        ModuleModel module = Fixture.blocking().model(Scope.all()).modules().get(0);
        return new ProjectModel(1, List.of(module), Scope.all(), null, List.of("json"),
                1, 1.5, timeoutConstMillis, 1000, null);
    }

    @Test
    void aBlockingMutantIsTimedOutRatherThanHangingTheRun() {
        AnalysisResult result = new AnalysisEngine(model(1_500), AnalysisEngine.Listener.SILENT)
                .analyse(null);

        assertFalse(result.mutants().isEmpty(), "the fixture has to yield mutants at all");
        assertTrue(result.count(MutantStatus.TIMED_OUT) > 0,
                "the mutant that removes the clamp blocks for ten minutes, so something has to "
                        + "stop it: " + result.mutants());
    }

    @Test
    void theRestOfTheRunStillProducesVerdicts() {
        AnalysisResult result = new AnalysisEngine(model(1_500), AnalysisEngine.Listener.SILENT)
                .analyse(null);

        assertTrue(result.count(MutantStatus.KILLED) + result.count(MutantStatus.SURVIVED) > 0,
                "killing one mutant's JVM must not cost the mutants after it: " + result.mutants());
        assertEquals(0, result.count(MutantStatus.RUN_ERROR),
                "the timeout is a verdict, not a failure to analyse: " + result.mutants());
    }

    @Test
    void aTimedOutMutantIsNeverCached() {
        // A wall-clock timeout is not reproducible, so storing one would be reporting a guess as
        // a result on every later run.
        AnalysisResult result = new AnalysisEngine(model(1_500), AnalysisEngine.Listener.SILENT)
                .analyse(null);

        List<Mutant> timedOut = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.TIMED_OUT)
                .toList();

        assertFalse(timedOut.isEmpty());
        timedOut.forEach(m -> assertFalse(m.status().isCacheable(),
                "a timeout must not be cacheable: " + m.key().asString()));
    }
}
