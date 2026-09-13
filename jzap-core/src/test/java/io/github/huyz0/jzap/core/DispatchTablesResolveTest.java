package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.agent.MutantOps;
import io.github.huyz0.jzap.core.mutator.ConditionalsBoundaryMutator;
import io.github.huyz0.jzap.core.mutator.MathMutator;
import io.github.huyz0.jzap.core.mutator.NegateConditionalsMutator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every dispatch call the schemata transformer can emit has to name a method that exists.
 *
 * <p>The transformer decides whether to seed a mutant from one table -- the mutator's own opcode
 * map -- and works out what to call from another: {@link MathNames} for arithmetic,
 * {@link Conditionals} for jumps. Nothing ties the two together, so an opcode present in one and
 * missing from the other emits a call to a method with a null name, or to a method that is not
 * there.
 *
 * <p>Neither failure is loud. A class referring to a missing method throws
 * {@link NoSuchMethodError} when the mutated code first runs, which the minion classifies as a
 * linkage problem and reports as NON_VIABLE -- indistinguishable, in the report, from a mutant the
 * JVM legitimately rejected. Every mutant of that operator would quietly stop being tested while
 * the score went up.
 *
 * <p>Checked by resolving each one against the real {@link MutantOps}, which is the same class the
 * analysis JVM loads.
 */
class DispatchTablesResolveTest {

    /** Every opcode ASM defines, so the tables are probed rather than trusted. */
    private static List<Integer> allOpcodes() {
        List<Integer> opcodes = new ArrayList<>();
        for (int opcode = 0; opcode <= 201; opcode++) {
            opcodes.add(opcode);
        }
        return opcodes;
    }

    private static void assertResolves(String name, String descriptor, int opcode) {
        assertNotNull(name, "no dispatch method name for opcode " + opcode);
        assertNotNull(descriptor, "no dispatch descriptor for opcode " + opcode);
        Set<String> candidates = new LinkedHashSet<>();
        for (Method method : MutantOps.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                candidates.add(Type.getMethodDescriptor(method));
            }
        }
        assertTrue(candidates.contains(descriptor),
                () -> "MutantOps." + name + descriptor + " does not exist (opcode " + opcode
                        + "); declared overloads: " + candidates);
    }

    @Test
    void everyArithmeticOpcodeAMutatorHandlesHasADispatchMethod() {
        int checked = 0;
        for (int opcode : allOpcodes()) {
            if (!MathMutator.handles(opcode)) {
                continue;
            }
            checked++;
            String name = MathNames.of(opcode);
            Type type = MathNames.typeOf(opcode);
            assertNotNull(type, "no operand type for opcode " + opcode);
            // The descriptor is built exactly as SchemataTransformer builds it.
            String descriptor = MathNames.isShiftWithIntDistance(opcode)
                    ? "(" + type.getDescriptor() + "II)" + type.getDescriptor()
                    : "(" + type.getDescriptor() + type.getDescriptor() + "I)" + type.getDescriptor();
            assertResolves(name, descriptor, opcode);
        }
        // 11 int and 11 long (add, sub, mul, div, rem, and, or, xor, shl, shr, ushr), plus 5
        // each for float and double, which have no bitwise or shift forms.
        assertEquals(32, checked, "MATH covers 32 opcodes");
    }

    @Test
    void everyNegationOpcodeHasADispatchMethod() {
        for (int opcode : List.of(Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG)) {
            Type type = switch (opcode) {
                case Opcodes.LNEG -> Type.LONG_TYPE;
                case Opcodes.FNEG -> Type.FLOAT_TYPE;
                case Opcodes.DNEG -> Type.DOUBLE_TYPE;
                default -> Type.INT_TYPE;
            };
            String name = switch (opcode) {
                case Opcodes.LNEG -> "lneg";
                case Opcodes.FNEG -> "fneg";
                case Opcodes.DNEG -> "dneg";
                default -> "ineg";
            };
            assertResolves(name, "(" + type.getDescriptor() + "I)" + type.getDescriptor(), opcode);
        }
    }

    @Test
    void everyJumpOpcodeAConditionalMutatorHandlesHasADispatchMethod() {
        int checked = 0;
        for (int opcode : allOpcodes()) {
            if (!NegateConditionalsMutator.handles(opcode)
                    && !ConditionalsBoundaryMutator.handles(opcode)) {
                continue;
            }
            checked++;
            assertResolves(Conditionals.nameOf(opcode), Conditionals.descriptorOf(opcode), opcode);
        }
        assertEquals(16, checked,
                "the two conditional mutators between them cover all 16 comparison jumps");
    }

    @Test
    void theIncrementDispatchExists() {
        assertResolves("increment", "(III)I", Opcodes.IINC);
    }
}
