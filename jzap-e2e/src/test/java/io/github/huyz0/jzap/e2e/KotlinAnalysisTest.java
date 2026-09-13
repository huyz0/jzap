package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.AnalysisEngine;
import io.github.huyz0.jzap.model.AnalysisResult;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantStatus;
import io.github.huyz0.jzap.model.Scope;
import io.github.huyz0.jzap.model.ScopeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kotlin end to end, with every expectation derived by hand from `Pricing.kt`.
 *
 * <p>The point of testing Kotlin separately is not that it compiles to bytecode — it does, and
 * mutation works on it unchanged. It is that kotlinc emits a great deal of code the developer
 * never wrote, and a mutant in that code is junk in the precise sense that matters: no mistake
 * anyone could make produces it, so nobody can act on it, and it stays in the report forever.
 */
class KotlinAnalysisTest {

    private static AnalysisResult result;
    private static int surchargeLine;

    @BeforeAll
    static void analyse() {
        Fixture fixture = Fixture.kotlin();
        result = new AnalysisEngine(fixture.model(Scope.all()),
                AnalysisEngine.Listener.SILENT).analyse(null);
        // Derived, not hard-coded: editing a comment in the fixture must not fail a test about
        // inlining.
        surchargeLine = fixture.lineContaining("ksample/Pricing.kt", "val surcharged = base + 5");
    }

    private static Set<String> mutatorsFor(String className, String method) {
        return result.mutants().stream()
                .filter(m -> m.key().className().equals(className))
                .filter(m -> m.key().methodName().equals(method))
                .map(m -> m.key().mutator())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    @Test
    void analysesAKotlinModule() {
        assertFalse(result.mutants().isEmpty(), "no mutants were found in the Kotlin fixture");
        assertTrue(result.testsDiscovered() >= 6,
                "expected the fixture's tests to be discovered, got " + result.testsDiscovered());
        assertEquals(List.of(), result.failingBaselineTests());
        assertEquals(0, result.count(MutantStatus.RUN_ERROR));
        assertEquals(0, result.count(MutantStatus.NON_VIABLE),
                "Kotlin bytecode should mutate as cleanly as Java's");
    }

    @Test
    void reportsAgainstKotlinSourceFiles() {
        assertTrue(result.mutants().stream().allMatch(m -> m.sourceFile().endsWith(".kt")),
                "mutants must point at .kt files, not an assumed .java name");
    }

    @Test
    void mutatesOrdinaryKotlinLogic() {
        // if (percent > 50), the arithmetic, and the return are all developer-written.
        assertTrue(mutatorsFor("ksample.Pricing", "applyDiscount")
                        .containsAll(Set.of("CONDITIONALS_BOUNDARY", "NEGATE_CONDITIONALS",
                                "MATH", "PRIMITIVE_RETURNS")),
                "got " + mutatorsFor("ksample.Pricing", "applyDiscount"));
        assertTrue(mutatorsFor("ksample.Pricing", "describe").contains("NEGATE_CONDITIONALS"),
                "a when-expression's branches are real decisions");
        assertTrue(mutatorsFor("ksample.Pricing", "label").contains("NEGATE_CONDITIONALS"),
                "?. and ?: are user-written null handling, not compiler scaffolding");
    }

    @Test
    void doesNotMutateGeneratedPropertyAccessors() {
        // `data class Order(val id: String, val amount: Int)` has no getter body to get wrong.
        assertEquals(Set.of(), mutatorsFor("ksample.Order", "getId"));
        assertEquals(Set.of(), mutatorsFor("ksample.Order", "getAmount"));
    }

    @Test
    void doesNotMutateDataClassMembers() {
        Set<String> generated = Set.of("component1", "component2", "copy", "copy$default",
                "equals", "hashCode", "toString");
        List<String> found = result.mutants().stream()
                .filter(m -> m.key().className().equals("ksample.Order"))
                .filter(m -> generated.contains(m.key().methodName()))
                .map(m -> m.key().asString())
                .toList();

        assertEquals(List.of(), found, "data class members are written by the compiler");
    }

    @Test
    void doesNotMutateForEachLoopScaffolding() {
        // `for (price in prices)` compiles to an Iterator.hasNext check. Negating it makes the
        // loop skip everything or never end, which is the same uninformative outcome as mutating
        // a loop counter in Java -- and the Java filter cannot see it, because there is no
        // increment to recognise.
        List<Mutant> loopConditionals = result.mutants().stream()
                .filter(m -> m.key().methodName().equals("totalOf"))
                .filter(m -> m.key().mutator().equals("NEGATE_CONDITIONALS"))
                .toList();

        assertEquals(List.of(), loopConditionals,
                "expected no mutants on the iterator check, got "
                        + loopConditionals.stream().map(m -> m.key().asString()).toList());
        assertTrue(mutatorsFor("ksample.Pricing", "totalOf").contains("MATH"),
                "but the loop body is still real code");
    }

    @Test
    void doesNotMutateNullCheckIntrinsics() {
        assertTrue(result.mutants().stream().noneMatch(m ->
                        m.description() != null && m.description().contains("Intrinsics")),
                "removing a compiler-inserted null check is not a fault anyone can commit");
    }

    @Test
    void theSurvivingMutantIsAGenuineGap() {
        List<Mutant> survivors = result.mutants().stream()
                .filter(m -> m.status() == MutantStatus.SURVIVED)
                .toList();

        // The tests check percent=10 and percent=90 but never 50 exactly, so moving the boundary
        // is invisible. Derived from the source, not from what jzap happens to report.
        assertEquals(1, survivors.size(),
                () -> "expected one survivor, got " + survivors.stream()
                        .map(m -> m.key().asString()).toList());
        assertEquals("CONDITIONALS_BOUNDARY", survivors.get(0).key().mutator());
        assertEquals("applyDiscount", survivors.get(0).key().methodName());
    }

    @Test
    void theFiltersCanBeSwitchedOff() {
        AnalysisResult unfiltered = new AnalysisEngine(
                Fixture.kotlin().model(new Scope(ScopeKind.ALL, null, null, "line", null,
                        List.of(), List.of(), List.of(), List.of("KOTLIN"), List.of())),
                AnalysisEngine.Listener.SILENT).analyse(null);

        assertTrue(unfiltered.mutants().size() > result.mutants().size(),
                "switching the Kotlin filters off should expose the generated constructs again");
        assertTrue(unfiltered.mutants().stream()
                        .anyMatch(m -> m.key().className().equals("ksample.Order")),
                "including the data class accessors");
    }

    @Test
    void mutatesInlineFunctionBodiesThroughTheirCallSites() {
        // withSurcharge is inlined into two callers. Its body's mutants live in those callers,
        // and both are killed by tests that call them.
        List<Mutant> inlined = result.mutants().stream()
                .filter(m -> m.key().methodName().startsWith("checkout"))
                .filter(m -> m.description().contains("inlined copy"))
                .toList();

        assertEquals(2, inlined.size(),
                () -> "expected one inlined mutant per call site, got "
                        + inlined.stream().map(m -> m.key().asString()).toList());
        assertTrue(inlined.stream().allMatch(m -> m.status() == MutantStatus.KILLED),
                "the tests exercise both call sites, so both copies must die");
    }

    @Test
    void inlinedMutantsReportTheInlineFunctionsOwnSourceLine() {
        List<Integer> lines = result.mutants().stream()
                .filter(m -> m.description().contains("inlined copy"))
                .map(m -> m.key().line())
                .distinct()
                .toList();

        // Line 40 is `val surcharged = base + 5` inside withSurcharge. In the bytecode these
        // copies carry synthetic line numbers 51 and 53, past the end of a 49-line file; without
        // translating them every inlined mutant points at a line that does not exist.
        assertEquals(List.of(surchargeLine), lines,
                "inlined mutants must point at the inline function's source, got " + lines);
    }

    @Test
    void theInlineFunctionsOwnBodyIsReportedAsUnreachable() {
        List<Mutant> declaration = result.mutants().stream()
                .filter(m -> m.key().methodName().equals("withSurcharge"))
                .toList();

        assertFalse(declaration.isEmpty(), "kotlinc emits a real method for Java callers");
        assertTrue(declaration.stream().allMatch(m -> m.status() == MutantStatus.NO_COVERAGE),
                () -> "Kotlin callers inline the body, so the emitted method never runs: "
                        + declaration.stream()
                                .map(m -> m.key().asString() + "=" + m.status()).toList());
    }

    @Test
    void anInlinedCopyAndItsDeclarationDoNotShareCoverage() {
        // Both report source line 40 of the same class. Keyed by line alone they would share a
        // coverage probe, and the unreachable declaration would look covered by whatever
        // exercised a call site.
        Set<MutantStatus> statuses = result.mutants().stream()
                .filter(m -> m.key().line() == surchargeLine)
                .map(Mutant::status)
                .collect(Collectors.toSet());

        assertEquals(Set.of(MutantStatus.KILLED, MutantStatus.NO_COVERAGE), statuses,
                "the call sites are covered and the declaration is not, at the same source line");
    }
}
