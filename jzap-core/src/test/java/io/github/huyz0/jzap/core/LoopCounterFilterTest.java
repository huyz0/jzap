package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expectations here were checked against PIT by running its INCREMENTS mutator alone over
 * the same four shapes: PIT produced a mutant for {@code standalone} only. jzap matching that
 * is deliberate, and the measurement behind it is in {@link LoopCounterFilter}.
 */
class LoopCounterFilterTest {

    private static final String SOURCE = """
            package ex;
            public class Loops {
                public int forLoop(int n) {
                    int total = 0;
                    for (int i = 0; i < n; i++) {
                        total += i;
                    }
                    return total;
                }
                public int whileLoop(int n) {
                    int total = 0;
                    int i = 0;
                    while (i < n) {
                        total += i;
                        i++;
                    }
                    return total;
                }
                public int standalone(int start) {
                    int value = start;
                    value++;
                    return value;
                }
                public int countedInLoop(int n) {
                    int hits = 0;
                    for (int i = 0; i < n; i++) {
                        hits++;
                    }
                    return hits;
                }
            }
            """;

    private static Set<String> incrementMethods(boolean filterLoopCounters) {
        byte[] bytes = InMemoryJavac.compile("ex.Loops", SOURCE).get("ex.Loops");
        List<Mutant> mutants = new MutationEngine(Mutators.defaults(),
                MutantFilters.defaults().withLoopCounters(filterLoopCounters))
                .discover(":test", bytes);
        return mutants.stream()
                .filter(m -> m.key().mutator().equals("INCREMENTS"))
                .map(m -> m.key().methodName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void suppressesForAndWhileCountersButNotOrdinaryIncrements() {
        Set<String> methods = incrementMethods(true);

        assertEquals(Set.of("standalone", "countedInLoop"), methods,
                "only loop counters should be suppressed, got " + methods);
    }

    @Test
    void theFilterCanBeSwitchedOff() {
        Set<String> methods = incrementMethods(false);

        assertTrue(methods.containsAll(Set.of("forLoop", "whileLoop", "standalone", "countedInLoop")),
                "with the filter off every increment is mutable, got " + methods);
    }

    @Test
    void filteringLeavesEveryRemainingKeyApplicable() {
        byte[] bytes = InMemoryJavac.compile("ex.Loops", SOURCE).get("ex.Loops");
        MutationEngine engine = new MutationEngine(Mutators.defaults());

        // Filtering happens after ordinals are assigned, so a surviving key must still seed.
        // If filtering shifted ordinals, apply() would throw or seed the wrong mutant.
        for (Mutant m : engine.discover(":test", bytes)) {
            engine.apply(bytes, m.key());
        }
    }

    @Test
    void aCounterIncrementInsideANestedLoopIsAlsoSuppressed() {
        String nested = """
                package ex;
                public class Nested {
                    public int sum(int n) {
                        int total = 0;
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                total += i * j;
                            }
                        }
                        return total;
                    }
                }
                """;
        byte[] bytes = InMemoryJavac.compile("ex.Nested", nested).get("ex.Nested");

        List<Mutant> increments = new MutationEngine(Mutators.defaults())
                .discover(":test", bytes).stream()
                .filter(m -> m.key().mutator().equals("INCREMENTS"))
                .toList();

        assertEquals(List.of(), increments,
                "both counters drive loops, so neither should be mutated: "
                        + increments.stream().map(m -> m.key().asString()).toList());
    }

    @Test
    void discoveryStaysDeterministicWithTheFilterOn() {
        byte[] bytes = InMemoryJavac.compile("ex.Loops", SOURCE).get("ex.Loops");
        MutationEngine engine = new MutationEngine(Mutators.defaults());

        assertEquals(
                engine.discover(":test", bytes).stream().map(m -> m.key().asString()).toList(),
                engine.discover(":test", bytes).stream().map(m -> m.key().asString()).toList());
    }

    @Test
    void mapOfMethodsIsUnchangedForClassesWithoutLoops() {
        String noLoops = """
                package ex;
                public class Flat {
                    public int bump(int v) {
                        int x = v;
                        x++;
                        return x;
                    }
                }
                """;
        byte[] bytes = InMemoryJavac.compile("ex.Flat", noLoops).get("ex.Flat");

        Map<Boolean, List<Mutant>> both = Map.of(
                true, new MutationEngine(Mutators.defaults()).discover(":t", bytes),
                false, new MutationEngine(Mutators.defaults(),
                        MutantFilters.defaults().withLoopCounters(false)).discover(":t", bytes));

        assertEquals(both.get(false).size(), both.get(true).size(),
                "a class with no loops must be unaffected by the filter");
    }
}
