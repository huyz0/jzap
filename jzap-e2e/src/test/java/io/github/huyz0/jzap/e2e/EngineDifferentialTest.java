package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standing gate for every optimisation: the fast engine must agree with the slow one.
 *
 * <p>PIT comparison cannot catch this class of bug, because it holds neither the mutant set nor the
 * tool fixed. This does: the same mutants, the same tests, the same machinery except the one thing
 * under test. A schemata encoding that got an operand order wrong, or an ordinal off by one, would
 * show up here as a changed verdict and nowhere else.
 *
 * <p>This is why docs/delivery-plan.md keeps the reference engine forever rather than deleting it
 * once something faster works.
 */
class EngineDifferentialTest {

    private static Map<String, String> verdicts(ProjectModel model) {
        AnalysisResult result = new AnalysisEngine(model, AnalysisEngine.Listener.SILENT)
                .analyse(null);
        return result.mutants().stream().collect(Collectors.toMap(
                m -> m.key().asString(),
                m -> m.status().name(),
                (a, b) -> a,
                java.util.LinkedHashMap::new));
    }

    private static void assertEnginesAgree(ProjectModel model, String what) {
        Map<String, String> naive = verdicts(model.withEngine("naive"));
        Map<String, String> schemata = verdicts(model.withEngine("schemata"));

        assertFalse(naive.isEmpty(), "no mutants were analysed for " + what);
        assertEquals(naive, schemata, "the engines disagree on " + what);
    }

    @Test
    void theEnginesAgreeOnTheJavaFixture() {
        assertEnginesAgree(new Fixture().model(Scope.all()), "the Java fixture");
    }

    @Test
    void theEnginesAgreeOnTheKotlinFixture() {
        // Kotlin is where an encoding mistake is most likely to hide: its bytecode has shapes javac
        // never produces, and the schemata pass has to reproduce discovery's ordinals across all
        // of them.
        assertEnginesAgree(Fixture.kotlin().model(Scope.all()), "the Kotlin fixture");
    }

    @Test
    void theEnginesAgreeAcrossModules() {
        assertEnginesAgree(Fixture.multiModule(), "the multi-module fixture");
    }

    @Test
    void theEnginesAgreeOnARunawayMutant() {
        // A schemata class has to carry the loop guard too, or a mutant that never returns falls
        // through to the wall-clock backstop and the verdict stops being reproducible.
        assertEnginesAgree(Fixture.hang().model(Scope.all()), "the hanging fixture");
    }

    @Test
    void theEnginesAgreeAtEveryThreadCount() {
        ProjectModel model = new Fixture().model(Scope.all());

        assertEquals(verdicts(model.withEngine("naive").withThreads(1)),
                verdicts(model.withEngine("schemata").withThreads(4)),
                "parallelism and schemata must compose without changing an answer");
    }

    @Test
    void schemataStillAnalysesTheMutantsItCannotCompileIn() {
        // VOID_METHOD_CALLS cannot be expressed without a branch, so those mutants take the old
        // path. They must still appear, with real verdicts.
        AnalysisResult result = new AnalysisEngine(
                new Fixture().model(Scope.all()).withEngine("schemata"),
                AnalysisEngine.Listener.SILENT).analyse(null);

        List<Mutant> routed = result.mutants().stream()
                .filter(m -> m.key().mutator().equals("VOID_METHOD_CALLS"))
                .toList();
        assertTrue(routed.stream().allMatch(m -> m.status() != null),
                "a mutant that cannot be compiled in must still be analysed, not dropped");
    }

    @Test
    void theReportNamesTheEngineThatProducedIt() {
        AnalysisResult schemata = new AnalysisEngine(
                new Fixture().model(Scope.all()).withEngine("schemata"),
                AnalysisEngine.Listener.SILENT).analyse(null);
        AnalysisResult naive = new AnalysisEngine(
                new Fixture().model(Scope.all()).withEngine("naive"),
                AnalysisEngine.Listener.SILENT).analyse(null);

        assertEquals("schemata", schemata.engine());
        assertEquals("naive", naive.engine());
    }
}
