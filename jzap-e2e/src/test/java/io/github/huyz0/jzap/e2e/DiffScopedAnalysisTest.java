package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.git.PatchScope;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.ChangedLines;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.model.ScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diff scoping end to end: the product's reason to exist, since free line-level diff mutation
 * testing does not exist for Gradle projects today.
 *
 * <p>Scoping is driven from a unified diff rather than a git range, which also proves the
 * engine never needs a repository — it consumes line ranges and nothing more.
 */
class DiffScopedAnalysisTest {

    private static final int ARITHMETIC_LINE = 13;   // return price - (price * percent / 100);
    private static final int CONDITIONAL_LINE = 10;  // if (percent > 50) {

    private static ProjectModel scoped(Scope scope) {
        return new Fixture().model(scope);
    }

    private static Scope diffScope() {
        return new Scope(ScopeKind.PATCH, null, null, "line", null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ChangedLines onlyLine(int line) {
        return PatchScope.parse(List.of(
                "--- a/sample/Discount.java",
                "+++ b/sample/Discount.java",
                "@@ -" + line + " +" + line + " @@",
                "-        old",
                "+        new"));
    }

    @Test
    void analysesOnlyTheMutantsOnChangedLines() {
        AnalysisResult result = new AnalysisEngine(scoped(diffScope()), AnalysisEngine.Listener.SILENT)
                .analyse(onlyLine(ARITHMETIC_LINE));

        assertFalse(result.mutants().isEmpty(), "the changed line carries mutants");
        Set<Integer> lines = result.mutants().stream()
                .map(m -> m.key().line())
                .collect(Collectors.toSet());
        assertEquals(Set.of(ARITHMETIC_LINE), lines,
                "only the changed line should be in scope, got lines " + lines);

        Set<String> classes = result.mutants().stream()
                .map(m -> m.key().className())
                .collect(Collectors.toSet());
        assertEquals(Set.of("sample.Discount"), classes,
                "the untouched class must not be analysed at all");
    }

    @Test
    void aDiffRunStillProducesRealVerdicts() {
        AnalysisResult result = new AnalysisEngine(scoped(diffScope()), AnalysisEngine.Listener.SILENT)
                .analyse(onlyLine(ARITHMETIC_LINE));

        for (Mutant m : result.mutants()) {
            assertEquals(MutantStatus.KILLED, m.status(),
                    () -> "every mutant on the arithmetic line is observable: " + m.key().asString());
        }
    }

    @Test
    void diffScopingAgreesWithTheFullRunOnTheSameMutants() {
        AnalysisResult full = new AnalysisEngine(scoped(Scope.all()), AnalysisEngine.Listener.SILENT)
                .analyse(null);
        AnalysisResult scoped = new AnalysisEngine(scoped(diffScope()), AnalysisEngine.Listener.SILENT)
                .analyse(onlyLine(CONDITIONAL_LINE));

        assertFalse(scoped.mutants().isEmpty());
        for (Mutant m : scoped.mutants()) {
            MutantStatus fromFullRun = full.mutants().stream()
                    .filter(f -> f.key().equals(m.key()))
                    .findFirst().orElseThrow().status();
            assertEquals(fromFullRun, m.status(),
                    () -> "narrowing the scope must not change a verdict: " + m.key().asString());
        }
    }

    @Test
    void nothingInScopeRunsNoTestsAtAll() {
        // The fast path a pull-request run hits most of the time: the diff touches no line that
        // carries a mutant, so the coverage phase is never even started.
        ChangedLines commentOnly = PatchScope.parse(List.of(
                "--- a/sample/Discount.java",
                "+++ b/sample/Discount.java",
                "@@ -2 +2 @@",
                "-package sample;",
                "+package sample; // touched"));

        AnalysisResult result = new AnalysisEngine(scoped(diffScope()), AnalysisEngine.Listener.SILENT)
                .analyse(commentOnly);

        assertTrue(result.mutants().isEmpty(), "no mutants live on that line");
        assertEquals(0, result.testsDiscovered(), "no test discovery should have happened");
        assertEquals(0L, result.timings().getOrDefault("coverage", 0L),
                "the coverage phase must be skipped entirely when nothing is in scope");
    }

    @Test
    void classGranularityWidensToTheWholeChangedClass() {
        Scope classScope = new Scope(ScopeKind.PATCH, null, null, "class", null,
                List.of(), List.of(), List.of(), List.of(), List.of());

        AnalysisResult result = new AnalysisEngine(scoped(classScope), AnalysisEngine.Listener.SILENT)
                .analyse(onlyLine(ARITHMETIC_LINE));

        Set<Integer> lines = result.mutants().stream()
                .map(m -> m.key().line())
                .collect(Collectors.toSet());
        assertTrue(lines.size() > 1,
                "class granularity should reach beyond the changed line, got " + lines);
        Set<String> classes = result.mutants().stream()
                .map(m -> m.key().className())
                .collect(Collectors.toSet());
        assertEquals(Set.of("sample.Discount"), classes,
                "but still only the changed class");
    }

    @Test
    void theScopeSummarySaysNothingWasCheckedOut() {
        AnalysisResult result = new AnalysisEngine(scoped(diffScope()), AnalysisEngine.Listener.SILENT)
                .analyse(onlyLine(ARITHMETIC_LINE));

        assertTrue(result.scopeSummary().contains("current compiled code"),
                "users must be told that a range selects scope and checks nothing out: "
                        + result.scopeSummary());
    }
}
