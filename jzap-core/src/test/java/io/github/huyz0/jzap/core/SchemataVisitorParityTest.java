package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The schemata pass and the mutating pass have to walk the instruction stream the same way.
 *
 * <p>Both decide whether a return mutation would be a no-op by looking at the instruction before
 * the return, and a mutator that declines a no-op also declines to take an ordinal. So if one
 * visitor notices a kind of instruction the other ignores, the two passes assign different
 * ordinals from that point on in the method -- and a mutant gets seeded under another mutant's
 * key, which means the verdict is reported against the wrong change.
 *
 * <p>That failure is invisible in the output: the key exists, the report looks ordinary, and only
 * a differential run against the reference engine would show it. It is also easy to reintroduce,
 * because the two visitors are separate classes with separate lists of overrides -- which is
 * exactly what this pins. {@code visitInvokeDynamicInsn} was missing from the schemata visitor
 * until this test was written.
 */
class SchemataVisitorParityTest {

    /**
     * Instruction callbacks a visitor overrides.
     *
     * <p>By name alone: what matters is whether the visitor takes an interest in that kind of
     * instruction at all, not what it then does about it.
     */
    private static Set<String> instructionCallbacksOverriddenBy(String className) throws Exception {
        Class<?> type = Class.forName(className);
        Set<String> declared = new LinkedHashSet<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().startsWith("visit") && m.getName().endsWith("Insn")) {
                declared.add(m.getName());
            }
        }
        // visitLabel and visitFrame are not named ...Insn but carry the same meaning here: both
        // mark a point where the previous instruction stops saying anything about the stack.
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals("visitLabel") || m.getName().equals("visitFrame")) {
                declared.add(m.getName());
            }
        }
        return new TreeSet<>(declared);
    }

    @Test
    void bothVisitorsTakeAnInterestInTheSameInstructions() throws Exception {
        Set<String> mutating = instructionCallbacksOverriddenBy(
                "io.github.huyz0.jzap.core.mutator.ReturnValueMutator$ReturnMutatingMethodVisitor");
        Set<String> schemata = instructionCallbacksOverriddenBy(
                "io.github.huyz0.jzap.core.SchemataTransformer$SchemataMethodVisitor");

        assertFalse(mutating.isEmpty(), "the mutating visitor was not found, so this proves nothing");
        assertEquals(mutating, schemata,
                "the two passes would track the instruction stream differently, so their ordinals "
                        + "would diverge and a mutant would be seeded under another mutant's key");
    }

    /** Guards the test itself: both names have to resolve, or the comparison is vacuous. */
    @Test
    void bothVisitorsAreMethodVisitors() throws Exception {
        for (String name : Arrays.asList(
                "io.github.huyz0.jzap.core.mutator.ReturnValueMutator$ReturnMutatingMethodVisitor",
                "io.github.huyz0.jzap.core.SchemataTransformer$SchemataMethodVisitor")) {
            assertEquals(MethodVisitor.class, Class.forName(name).getSuperclass(), name);
        }
    }
}
