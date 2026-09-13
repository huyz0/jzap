package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Negates a local variable increment, so {@code i++} becomes {@code i--}. PIT's INCREMENTS. */
public final class IncrementsMutator implements Mutator {

    public static final String ID = "INCREMENTS";

    /**
     * Whether negating this increment yields a different program.
     *
     * <p>Two increments cannot be negated into anything new. {@code IINC} carries a signed 16-bit
     * operand, so the negation of {@code -32768} is {@code 32768}, which does not fit -- and ASM
     * writes the low sixteen bits without complaint, so the mutant comes out as {@code -32768}
     * again. Zero negates to itself. Either way the mutant is byte-identical to the original, and
     * a mutant identical to the original cannot be killed by anything: it is reported as surviving
     * in every run forever, pointing a reviewer at code that is fine.
     *
     * <p>Both shapes come from plain javac: {@code i -= 32768} compiles to {@code iinc_w -32768}
     * and {@code i += 0} to {@code iinc 0}.
     *
     * <p>Public and consulted rather than copied, because the schemata pass has to decline in
     * exactly the same places. A mutator that declines also declines to take an ordinal, so a
     * disagreement here would shift every later ordinal in the method.
     */
    public static boolean canNegate(int increment) {
        return increment != 0
                && -increment >= Short.MIN_VALUE
                && -increment <= Short.MAX_VALUE;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitIincInsn(int varIndex, int increment) {
                if (!canNegate(increment)) {
                    // Deliberately does not register a mutant, so every pass agrees on ordinals.
                    super.visitIincInsn(varIndex, increment);
                    return;
                }
                String desc = "changed increment from " + increment + " to " + (-increment);
                if (ctx.shouldMutate(ID, desc)) {
                    super.visitIincInsn(varIndex, -increment);
                } else {
                    super.visitIincInsn(varIndex, increment);
                }
            }
        };
    }
}
