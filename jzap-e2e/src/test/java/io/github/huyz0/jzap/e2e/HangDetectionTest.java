package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mutant that never returns must be detected by what it does, not by how long it takes.
 *
 * <p>A wall-clock timeout makes the verdict depend on machine load, which makes reports differ
 * between runs and unusable as build-cache outputs. Counting loop iterations gives the same
 * answer every time, and gives it far sooner.
 */
class HangDetectionTest {

    private static ProjectModel hangModel(long timeoutConstMillis) {
        ProjectModel base = Fixture.hang().model(Scope.all());
        return new ProjectModel(1, base.modules(), base.scope(), base.cache(), base.reporters(),
                1, base.timeoutFactor(), timeoutConstMillis, base.maxMutantsPerMinion(), base.engine());
    }

    private static AnalysisResult analyse(long timeoutConstMillis) {
        return new AnalysisEngine(hangModel(timeoutConstMillis), AnalysisEngine.Listener.SILENT)
                .analyse(null);
    }

    @Test
    void theRunawayMutantIsDetectedAndTheRestAreUnaffected() {
        AnalysisResult result = analyse(4000);

        List<Mutant> timedOut = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.TIMED_OUT)
                .toList();
        assertEquals(1, timedOut.size(),
                () -> "expected exactly one runaway mutant, got " + result.mutants().stream()
                        .map(m -> m.key().asString() + "=" + m.status()).toList());
        assertEquals("countdown", timedOut.get(0).key().methodName());
        assertTrue(result.count(MutantStatus.KILLED) > 0, "the rest of the class must still run");
        assertEquals(0, result.count(MutantStatus.RUN_ERROR));
    }

    @Test
    void detectionDoesNotDependOnTheWallClock() {
        // A one-second wall-clock budget is not enough for a JVM to notice a hang by timing on a
        // loaded machine, yet the verdict is the same: the guard decided it by counting.
        AnalysisResult impatient = analyse(1);
        AnalysisResult patient = analyse(30_000);

        assertEquals(1, impatient.count(MutantStatus.TIMED_OUT));
        assertEquals(1, patient.count(MutantStatus.TIMED_OUT));
        assertEquals(
                patient.mutants().stream().map(m -> m.key().asString() + "=" + m.status()).toList(),
                impatient.mutants().stream().map(m -> m.key().asString() + "=" + m.status()).toList(),
                "the whole report must be identical whatever the wall-clock budget");
    }

    @Test
    void aRunawayMutantIsFoundFastRatherThanAfterTheTimeout() {
        long start = System.nanoTime();
        AnalysisResult result = analyse(30_000);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(1, result.count(MutantStatus.TIMED_OUT));
        assertTrue(elapsedMillis < 30_000,
                "the whole run took " + elapsedMillis + "ms, which suggests the wall-clock "
                        + "backstop decided this rather than the loop guard");
    }

    @Test
    void legitimateLoopsAreNotMistakenForRunawayOnes() {
        // hang.Countdown's own tests loop normally. If the limit were too tight, the unmutated
        // behaviour would trip it and mutants would be reported as runaway wholesale.
        AnalysisResult result = analyse(30_000);

        assertEquals(List.of(), result.failingBaselineTests());
        assertTrue(result.count(MutantStatus.TIMED_OUT) == 1,
                "only the genuinely runaway mutant should trip the guard");
    }
}
