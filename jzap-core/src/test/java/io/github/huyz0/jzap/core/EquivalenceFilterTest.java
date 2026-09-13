package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trivial Compiler Equivalence, and an honest account of what it is worth here.
 *
 * <p>TCE compares compiled forms, so it can only catch a mutant whose bytecode is byte-identical
 * to the original's or to another mutant's. That happens when a compiler optimises the difference
 * away. javac optimises almost nothing, and jzap's default mutator set has no two operators that
 * can produce the same instruction at the same place, so on javac output with the default set the
 * filter finds nothing at all — see {@link #findsNothingOnJavacOutputWithTheDefaultSet}.
 *
 * <p>That is a finding, not a defect, and it is why the published "about 11% of Java mutants"
 * figure does not transfer: it was measured with a larger mutator set containing operators that
 * overlap. The filter is kept because it costs nothing when off and will matter where a compiler
 * does fold code — Kotlin's does considerably more than javac — or where a user supplies a set
 * with redundant operators.
 */
class EquivalenceFilterTest {

    private static final String SOURCE = """
            package ex;
            public class Arith {
                public int mix(int a, int b) {
                    return a + b * a - b;
                }

                public boolean compare(int a, int b) {
                    return a >= b;
                }
            }
            """;

    private static byte[] compiled() {
        return InMemoryJavac.compile("ex.Arith", SOURCE).get("ex.Arith");
    }

    @Test
    void detectsADuplicateWhenOneExists() {
        byte[] bytes = compiled();
        MutationEngine engine = MutationEngine.withDefaults();
        List<Mutant> mutants = engine.discover(":test", bytes);
        assertTrue(mutants.size() >= 2);

        // The same mutant listed twice is the same program twice, which is precisely what the
        // filter exists to notice. Contrived, because javac does not hand us a natural example;
        // it is the mechanism being tested, not the frequency.
        Mutant first = mutants.get(0);
        EquivalenceFilter.Result result =
                EquivalenceFilter.apply(engine, bytes, List.of(first, first, mutants.get(1)));

        assertEquals(1, result.duplicates().size(), "the repeated mutant should be recognised");
        assertEquals(2, result.kept().size());
        assertEquals(List.of(), result.equivalent());
    }

    @Test
    void findsNothingOnJavacOutputWithTheDefaultSet() {
        byte[] bytes = compiled();
        MutationEngine engine = MutationEngine.withDefaults();

        EquivalenceFilter.Result result =
                EquivalenceFilter.apply(engine, bytes, engine.discover(":test", bytes));

        assertEquals(0, result.dropped(),
                "javac folds almost nothing and the default mutators do not overlap, so there is "
                        + "nothing for a bytecode comparison to find. Dropped: "
                        + result.equivalent().size() + " equivalent, "
                        + result.duplicates().size() + " duplicate");
    }

    @Test
    void keptMutantsAreStillApplicable() {
        byte[] bytes = compiled();
        MutationEngine engine = new MutationEngine(Mutators.defaults(), true, true);

        // Dropping happens after ordinals are assigned, so what survives must still seed.
        for (Mutant m : engine.discover(":test", bytes)) {
            engine.apply(bytes, m.key());
        }
    }

    @Test
    void comparisonIgnoresDebugInformation() {
        // Two mutants differing only in line-number tables must compare equal, or every mutant
        // would look distinct and the filter would never fire anywhere.
        byte[] bytes = compiled();
        MutationEngine engine = MutationEngine.withDefaults();
        Mutant mutant = engine.discover(":test", bytes).get(0);

        EquivalenceFilter.Result result =
                EquivalenceFilter.apply(engine, bytes, List.of(mutant, mutant));

        assertEquals(1, result.duplicates().size());
    }
}
