package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Reusing an analysis JVM between mutants is an optimisation with a correctness question attached:
 * static state set by one mutant's run is still there for the next one's.
 *
 * <p>Giving every mutant its own JVM is the sound answer and a slow one, so jzap bounds the drift
 * instead, recycling the JVM every {@code maxMutantsPerMinion} mutants. Whether that is good enough
 * is not something to assume — it is measured here, by running each fixture both ways and comparing
 * every verdict.
 *
 * <p>The schemata engine makes this sharper, not softer: it no longer redefines a class per mutant,
 * so a JVM lives through more mutants than it used to.
 */
class IsolationSoundnessTest {

    private static Map<String, String> verdicts(ProjectModel model) {
        AnalysisResult result = new AnalysisEngine(model, AnalysisEngine.Listener.SILENT)
                .analyse(null);
        return result.mutants().stream().collect(Collectors.toMap(
                m -> m.key().asString(),
                m -> m.status().name(),
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private static void assertReuseIsSound(Fixture fixture, String what) {
        Map<String, String> isolated = verdicts(fixture.model(Scope.all(), 1));
        Map<String, String> reused = verdicts(fixture.model(Scope.all(), 100));

        assertFalse(isolated.isEmpty(), "no mutants were analysed for " + what);
        assertEquals(isolated, reused,
                "reusing an analysis JVM changed a verdict for " + what
                        + ", which means state from one mutant reached another");
    }

    @Test
    void reuseIsSoundOnOrdinaryCode() {
        assertReuseIsSound(new Fixture(), "the Java fixture");
    }

    @Test
    void reuseIsSoundOnCodeThatKeepsStaticState() {
        // A running counter and a memoised field: both survive from one mutant's run to the next.
        // If bounded recycling were not enough, this is the fixture where it would show.
        assertReuseIsSound(Fixture.stateful(), "the stateful fixture");
    }

    @Test
    void reuseIsSoundOnKotlin() {
        assertReuseIsSound(Fixture.kotlin(), "the Kotlin fixture");
    }

    @Test
    void aFreshJvmPerMutantIsAlwaysAvailable() {
        // The slow, sound mode has to keep working, because it is the reference every other
        // configuration is compared against.
        AnalysisResult isolated = new AnalysisEngine(
                Fixture.stateful().model(Scope.all(), 1), AnalysisEngine.Listener.SILENT)
                .analyse(null);

        assertFalse(isolated.mutants().isEmpty());
        assertEquals(0, isolated.count(io.github.huyz0.jzap.model.MutantStatus.RUN_ERROR));
    }
}
