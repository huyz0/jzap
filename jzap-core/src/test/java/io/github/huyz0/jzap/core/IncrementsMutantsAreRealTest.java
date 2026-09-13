package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.IncrementsMutator;
import io.github.huyz0.jzap.model.Mutant;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An INCREMENTS mutant has to be a different program from the one it mutates.
 *
 * <p>Negating an increment is not always possible. {@code IINC} carries a signed 16-bit operand, so
 * the negation of {@code -32768} is {@code 32768}, which does not fit -- and ASM writes the low
 * sixteen bits without complaint, producing {@code -32768} again. Negating {@code 0} is a no-op for
 * the same reason of arithmetic. Either way the mutant compiles to bytecode identical to the
 * original, which no test can distinguish and no test can kill, so it is reported as surviving in
 * every run forever: a permanent false alarm pointing at code that is fine.
 *
 * <p>Both come out of plain javac -- {@code i -= 32768} compiles to {@code iinc_w -32768} and
 * {@code i += 0} to {@code iinc 0} -- so neither is hypothetical.
 *
 * <p>This is the rule {@code ReturnValueMutator} already applies to a return mutant that would
 * replace a value with the value already there. Every pass has to agree about declining, because a
 * mutator that declines also declines to take an ordinal.
 */
class IncrementsMutantsAreRealTest {

    private static final String SOURCE = """
            package ex;

            public class Counters {

                public int ordinary(int n) {
                    int i = n;
                    i++;
                    return i;
                }

                public int byMinShort(int n) {
                    int i = n;
                    i -= 32768;
                    return i;
                }

                public int byZero(int n) {
                    int i = n;
                    i += 0;
                    return i;
                }

                public int byMaxShort(int n) {
                    int i = n;
                    i += 32767;
                    return i;
                }
            }
            """;

    private static List<Mutant> increments() {
        byte[] original = InMemoryJavac.compile("ex.Counters", SOURCE).get("ex.Counters");
        return MutationEngine.withDefaults().discover(":t", original).stream()
                .filter(m -> m.key().mutator().equals(IncrementsMutator.ID))
                .toList();
    }

    private static boolean hasIncrementIn(String method) {
        return increments().stream().anyMatch(m -> m.key().methodName().equals(method));
    }

    @Test
    void anOrdinaryIncrementIsStillMutated() {
        assertTrue(hasIncrementIn("ordinary"), "i++ has to keep producing a mutant");
        assertTrue(hasIncrementIn("byMaxShort"),
                "32767 negates to -32767, which fits, so that mutant is real");
    }

    @Test
    void anIncrementWhoseNegationDoesNotFitIsNotSeeded() {
        assertFalse(hasIncrementIn("byMinShort"),
                "the negation of -32768 truncates back to -32768, so the mutant is the original");
    }

    @Test
    void anIncrementOfZeroIsNotSeeded() {
        assertFalse(hasIncrementIn("byZero"), "negating zero changes nothing");
    }

    /**
     * The property behind all three, checked against the bytecode rather than the inventory:
     * every mutant this engine offers has to differ from the class it came from.
     */
    @Test
    void everyMutantDiffersFromTheOriginal() {
        Map<String, byte[]> compiled = InMemoryJavac.compile("ex.Counters", SOURCE);
        byte[] original = compiled.get("ex.Counters");
        MutationEngine engine = MutationEngine.withDefaults();

        for (Mutant mutant : engine.discover(":t", original)) {
            assertFalse(Arrays.equals(original, engine.apply(original, mutant.key())),
                    () -> mutant.key().asString() + " compiles to the original's bytecode, so "
                            + "nothing can ever kill it");
        }
    }

    @Test
    void theRuleIsStatedOnceAndCoversTheWholeOperandRange() {
        assertFalse(IncrementsMutator.canNegate(0));
        assertFalse(IncrementsMutator.canNegate(Short.MIN_VALUE));
        assertTrue(IncrementsMutator.canNegate(Short.MAX_VALUE));
        assertTrue(IncrementsMutator.canNegate(1));
        assertTrue(IncrementsMutator.canNegate(-1));
        assertEquals(-32768, Short.MIN_VALUE, "the operand really is a signed 16-bit value");
    }
}
