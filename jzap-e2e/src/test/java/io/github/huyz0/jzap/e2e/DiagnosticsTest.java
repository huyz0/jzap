package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.model.ScopeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        final List<String> phases = new ArrayList<>();

        @Override
        public void phase(String name, String detail) {
            phases.add(name + (detail == null ? "" : ": " + detail));
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
        return analyse(model, null);
    }

    private static Recorder analyse(ProjectModel model, ChangedLines changed) {
        Recorder recorder = new Recorder();
        new AnalysisEngine(model, recorder).analyse(changed);
        return recorder;
    }

    private static AnalysisResult resultOf(ProjectModel model, ChangedLines changed) {
        return new AnalysisEngine(model, new Recorder()).analyse(changed);
    }

    /** A scope naming a file the fixture does not contain, so no mutant is in it. */
    private static ChangedLines nothingInScope() {
        ChangedLines changed = ChangedLines.empty();
        changed.add("nothing/Touched.java", 1);
        return changed;
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
    void aCodePathThatExistsButHoldsNoClassesSaysSo(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectories(dir.resolve("classes"));
        ModuleModel module = new ModuleModel(":empty",
                List.of(empty.toString()), List.of("src/main/java"),
                List.of(), List.of(), null, List.of(), null);

        Recorder recorder = analyse(withModule(module));

        assertTrue(recorder.warnings.stream().anyMatch(w -> w.contains("contain no class files")),
                "an existing but empty output directory is a different mistake from a missing one: "
                        + recorder.warnings);
        assertTrue(recorder.warnings.stream().anyMatch(w -> w.contains("--dry-run")),
                "and the next step is to see what was actually resolved: " + recorder.warnings);
    }

    @Test
    void anIncludePatternThatMatchesNothingIsNamedInTheWarning(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectories(dir.resolve("classes"));
        ModuleModel module = new ModuleModel(":empty",
                List.of(empty.toString()), List.of("src/main/java"),
                List.of(), List.of(), null, List.of(), null);
        ProjectModel model = new ProjectModel(1, List.of(module),
                new Scope(ScopeKind.ALL, null, null, "line", null,
                        List.of("nothing.matches.*"), List.of(), List.of(), List.of(), List.of()),
                null, List.of("json"), 1, 1.5, 4000, 100, null);

        Recorder recorder = analyse(model);

        assertTrue(recorder.warnings.stream().anyMatch(w -> w.contains("nothing.matches.*")),
                "a glob that excluded everything is the likeliest cause, so it has to be shown: "
                        + recorder.warnings);
    }

    @Test
    void aDiffRunWithNothingInScopeSaysThatPlainlyRatherThanWarning() {
        // No changed line carries a mutant: the healthy case for a diff run, and the one that
        // must not read as a failure.
        Recorder recorder = analyse(new Fixture().model(Scope.all()),
                nothingInScope());

        assertTrue(recorder.phases.stream().anyMatch(p -> p.startsWith("skipped")),
                "the run is skipped, not failed: " + recorder.phases);
        assertFalse(recorder.warnings.stream().anyMatch(w -> w.contains("none yielded a mutant")),
                "nothing here is wrong, so nothing should be warned about: " + recorder.warnings);
    }

    @Test
    void aRunWithNothingInScopeStillReportsItsScopeAndWritesNoVerdicts() {
        AnalysisResult result = resultOf(new Fixture().model(Scope.all()),
                nothingInScope());

        assertTrue(result.mutants().isEmpty());
        assertEquals(0, result.testsDiscovered(), "no test needs to be run to know this");
        assertTrue(result.scopeSummary().contains("all mutants"), result.scopeSummary());
    }

    @Test
    void aDiffScopeDescribesTheRangeAndSaysNothingWasCheckedOut() {
        ProjectModel model = new Fixture().model(
                new Scope(ScopeKind.DIFF, "HEAD~1", null, "line", null,
                        List.of(), List.of(), List.of(), List.of(), List.of()));

        AnalysisResult result = resultOf(model,
                nothingInScope());

        String summary = result.scopeSummary();
        assertTrue(summary.contains("HEAD~1"), summary);
        assertTrue(summary.contains(Scope.LOCAL), "an unset tip means uncommitted work: " + summary);
        assertTrue(summary.contains("1 lines in 1 files"), summary);
        assertTrue(summary.contains("nothing was checked out"),
                "the semantic both Mull and arcmutate document as a source of misleading results: "
                        + summary);
    }

    @Test
    void aPatchScopeNamesThePatchItCameFrom(@TempDir Path dir) throws Exception {
        Path patch = Files.writeString(dir.resolve("change.patch"), "");
        ProjectModel model = new Fixture().model(
                new Scope(ScopeKind.PATCH, null, null, "line", patch.toString(),
                        List.of(), List.of(), List.of(), List.of(), List.of()));

        AnalysisResult result = resultOf(model,
                nothingInScope());

        assertTrue(result.scopeSummary().contains(patch.toString()), result.scopeSummary());
        assertTrue(result.scopeSummary().contains("current compiled code"),
                result.scopeSummary());
    }

    @Test
    void aMultiModuleRunAnnouncesHowManyModulesItIsAnalysing() {
        Recorder recorder = analyse(Fixture.multiModule(),
                nothingInScope());

        assertTrue(recorder.phases.stream().anyMatch(p -> p.equals("discovery: 2 modules")),
                "one pass over every module, and the count is worth saying: " + recorder.phases);
    }

    @Test
    void aSingleModuleRunNamesItRatherThanCountingIt() {
        Recorder recorder = analyse(new Fixture().model(Scope.all()),
                nothingInScope());

        assertTrue(recorder.phases.stream().anyMatch(p -> p.contains("sample-java")),
                recorder.phases.toString());
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
