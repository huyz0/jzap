package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional reduction filters: dedup, arid code, and one mutant per line.
 *
 * <p>Each is measured against the unfiltered set rather than against a recorded number, because
 * what matters is what a filter removes relative to what would otherwise be analysed.
 */
class ReductionFilterTest {

    private static final String SOURCE = """
            package ex;
            public class Reports {
                public int busy(int a, int b) {
                    return a + b * a - b;
                }

                public void announce(java.io.PrintStream out, int value) {
                    out.println("value is " + value);
                }

                public int mixed(java.io.PrintStream out, int value) {
                    out.println("checking");
                    return value * 2;
                }

                public void work(StringBuilder sink, int value) {
                    sink.setLength(0);
                }
            }
            """;

    private static byte[] compiled() {
        return InMemoryJavac.compile("ex.Reports", SOURCE).get("ex.Reports");
    }

    private static List<Mutant> discover(boolean dedup, boolean arid, boolean onePerLine) {
        return new MutationEngine(Mutators.defaults(), true, dedup, arid, onePerLine)
                .discover(":test", compiled());
    }

    private static Set<String> mutatorsIn(List<Mutant> mutants, String method) {
        return mutants.stream()
                .filter(m -> m.key().methodName().equals(method))
                .map(m -> m.key().mutator())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    @Test
    void aridDropsLoggingCallsAndMethodsThatOnlyReport() {
        List<Mutant> unfiltered = discover(false, false, false);
        List<Mutant> filtered = discover(false, true, false);

        assertFalse(mutatorsIn(unfiltered, "announce").isEmpty(),
                "the reporting method should have mutants without the filter");
        assertEquals(Set.of(), mutatorsIn(filtered, "announce"),
                "a void method whose only call is printing exists to report, not to decide");
        assertFalse(mutatorsIn(filtered, "busy").isEmpty(),
                "arithmetic is untouched by the arid filter");
    }

    @Test
    void aridLeavesMethodsThatDoMoreThanReport() {
        List<Mutant> filtered = discover(false, true, false);

        // mixed() prints and then computes. One non-arid decision is enough to keep the whole
        // method: over-suppression is invisible in the report, so the filter stays conservative.
        assertTrue(mutatorsIn(filtered, "mixed").contains("MATH"),
                "the computation in a method that also logs must still be mutated");
        assertFalse(mutatorsIn(filtered, "mixed").contains("VOID_METHOD_CALLS"),
                "but the logging call inside it is still arid");
    }

    @Test
    void aridDoesNotTouchOrdinaryVoidCalls() {
        List<Mutant> filtered = discover(false, true, false);

        assertTrue(mutatorsIn(filtered, "work").contains("VOID_METHOD_CALLS"),
                "setLength is not logging; removing it is a real fault");
    }

    @Test
    void onePerLineKeepsExactlyOneMutantPerLine() {
        List<Mutant> reduced = discover(false, false, true);

        Map<String, Long> perLine = reduced.stream().collect(Collectors.groupingBy(
                m -> m.key().methodName() + ":" + m.key().line(), Collectors.counting()));
        assertTrue(perLine.values().stream().allMatch(count -> count == 1L),
                "expected one mutant per line, got " + perLine);
        assertTrue(reduced.size() < discover(false, false, false).size(),
                "the busy line carries several mutants, so something must have been dropped");
    }

    @Test
    void dedupDropsMutantsIdenticalToTheOriginalOrToEachOther() {
        List<Mutant> unfiltered = discover(false, false, false);
        List<Mutant> deduped = discover(true, false, false);

        assertTrue(deduped.size() <= unfiltered.size());
        // Every surviving mutant must still be applicable: dedup runs after ordinals are
        // assigned, so dropping must not shift the keys of what remains.
        MutationEngine engine = new MutationEngine(Mutators.defaults(), true, true);
        byte[] bytes = compiled();
        for (Mutant m : deduped) {
            engine.apply(bytes, m.key());
        }
    }

    @Test
    void dedupIsDeterministic() {
        assertEquals(
                discover(true, false, false).stream().map(m -> m.key().asString()).toList(),
                discover(true, false, false).stream().map(m -> m.key().asString()).toList());
    }

    @Test
    void filtersCompose() {
        List<Mutant> everything = discover(true, true, true);
        List<Mutant> nothing = discover(false, false, false);

        assertTrue(everything.size() < nothing.size());
        Map<String, Long> perLine = everything.stream().collect(Collectors.groupingBy(
                m -> m.key().methodName() + ":" + m.key().line(), Collectors.counting()));
        assertTrue(perLine.values().stream().allMatch(count -> count == 1L), perLine.toString());
        assertEquals(Set.of(), mutatorsIn(everything, "announce"));
    }

    @Test
    void theAridRulesAreDataNotCode() {
        assertFalse(AridFilter.rules().isEmpty(), "the rule file should have been loaded");
        assertTrue(AridFilter.rules().stream().anyMatch(rule -> rule.startsWith("org/slf4j/")),
                "expected the common logging frameworks to be covered: " + AridFilter.rules());
    }
}
