package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Only one mutant may be live at a time, including across the classes one worker analyses in turn.
 *
 * <p>Under the schemata engine a worker installs a schemata class per class it analyses and selects
 * a mutant with one global index, where the indices are numbered from zero within each class. Those
 * two facts do not compose on their own: a schemata class left installed from an earlier class
 * answers to the index being selected for the current one, so two mutations run at once and a
 * mutant's verdict is decided partly by a mutation in another class.
 *
 * <p>What that produces is the worst kind of wrong answer this tool can give -- a mutant the tests
 * do not detect, reported as killed -- because the score then overstates how good the suite is.
 */
class SchemataIsolationTest {

    private static AnalysisResult analyse(ProjectModel model) {
        return new AnalysisEngine(model, AnalysisEngine.Listener.SILENT).analyse(null);
    }

    private static Map<String, String> verdicts(AnalysisResult result) {
        return result.mutants().stream().collect(Collectors.toMap(
                m -> m.key().asString(),
                m -> m.status().name(),
                (a, b) -> a,
                LinkedHashMap::new));
    }

    /**
     * The decisive assertion: a mutant no assertion can reach must survive.
     *
     * <p>{@code Beta.untested} is called by the fixture's test and its result is never checked, so
     * nothing in that test can distinguish the mutant from the original. The only way it can come
     * back killed is if something other than that mutant made the test fail.
     */
    @Test
    void aMutantNoAssertionCoversSurvivesEvenAfterAnotherClassWasAnalysed() {
        AnalysisResult result = analyse(Fixture.crosstalk().model(Scope.all()));

        List<Mutant> untested = result.mutants().stream()
                .filter(m -> m.key().className().equals("crosstalk.Beta"))
                .filter(m -> m.key().methodName().equals("untested"))
                .toList();

        assertFalse(untested.isEmpty(), "the fixture is meant to produce a mutant in Beta.untested");
        for (Mutant mutant : untested) {
            assertEquals(MutantStatus.SURVIVED, mutant.status(),
                    "nothing asserts on Beta.untested, so " + mutant.key().asString()
                            + " cannot be killed by its own tests. Coming back "
                            + mutant.status() + " means a mutant from another class was still "
                            + "live while this one was being analysed");
        }
    }

    /**
     * The same property stated as a comparison, so a regression cannot hide by being wrong in both
     * modes: one JVM per mutant leaves nowhere for a stale schemata class to live.
     */
    @Test
    void analysingTwoClassesInOneJvmAgreesWithAJvmPerMutant() {
        Fixture fixture = Fixture.crosstalk();
        Map<String, String> isolated = verdicts(analyse(fixture.model(Scope.all(), 1)));
        Map<String, String> shared = verdicts(analyse(fixture.model(Scope.all())));

        assertFalse(isolated.isEmpty(), "no mutants were analysed");
        assertEquals(isolated, shared,
                "analysing both classes in one analysis JVM changed a verdict, so a mutant in the "
                        + "class analysed first was still selectable while the second was analysed");
    }
}
