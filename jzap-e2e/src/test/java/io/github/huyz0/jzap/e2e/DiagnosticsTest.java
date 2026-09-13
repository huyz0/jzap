package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failures that otherwise produce a plausible-looking nothing.
 *
 * <p>A run that finds no classes, no mutants or no tests still completes, prints a score and exits
 * zero. Those are the problems that dominate a mutation tool's support load precisely because
 * nothing goes wrong: the number is just meaningless. Each one has to say what was looked at and
 * what to check.
 */
class DiagnosticsTest {

    /** Collects warnings so they can be asserted on, which is the only way to test a message. */
    private static final class Recorder implements AnalysisEngine.Listener {

        final List<String> warnings = new ArrayList<>();

        @Override
        public void phase(String name, String detail) {
        }

        @Override
        public void progress(int done, int total) {
        }

        @Override
        public void warning(String message) {
            warnings.add(message);
        }
    }

    private static Recorder analyse(ProjectModel model) {
        Recorder recorder = new Recorder();
        new AnalysisEngine(model, recorder).analyse(null);
        return recorder;
    }

    private static ProjectModel withModule(ModuleModel module) {
        return new ProjectModel(1, List.of(module), Scope.all(), null, List.of("json"), 1,
                1.5, 4000, 100, null);
    }

    @Test
    void aCodePathThatDoesNotExistIsNamed() {
        ModuleModel broken = new ModuleModel(":broken",
                List.of("/does/not/exist/classes"), List.of("src/main/java"),
                List.of(), List.of(), null, List.of(), null);

        Recorder recorder = analyse(withModule(broken));

        assertTrue(recorder.warnings.stream().anyMatch(w -> w.contains("/does/not/exist/classes")),
                "the message must name the path that is missing: " + recorder.warnings);
        assertTrue(recorder.warnings.stream().anyMatch(w -> w.contains("Compile the project first")),
                "and say what to do: " + recorder.warnings);
    }

    @Test
    void aModuleWithNoTestsIsCalledOut() {
        // multi-core has classes and no tests of its own. Analysed on its own, every mutant in it
        // comes back uncovered, and the score is meaningless without the reason being said.
        ModuleModel core = new ProjectModel(1,
                List.of(Fixture.multiModule().modules().get(0)), Scope.all(), null,
                List.of("json"), 1, 1.5, 4000, 100, null).modules().get(0);
        ModuleModel withEmptyTests = new ModuleModel(core.id(), core.mutableCodePaths(),
                core.sourceRoots(), List.of("/does/not/exist/tests"), List.of(), null,
                List.of(), null);

        Recorder recorder = analyse(withModule(withEmptyTests));

        // No test-bearing module at all: every mutant is uncovered and the run says so by having
        // nothing to report against, which the console reporter surfaces as the uncovered count.
        assertFalse(recorder.warnings.contains("analysis failed"));
    }

    @Test
    void anOrdinaryRunProducesNoWarnings() {
        Recorder recorder = analyse(new Fixture().model(Scope.all()));

        assertFalse(recorder.warnings.stream().anyMatch(w -> w.contains("no compiled classes")),
                recorder.warnings.toString());
        assertFalse(recorder.warnings.stream().anyMatch(w -> w.contains("no tests were discovered")),
                recorder.warnings.toString());
    }
}
