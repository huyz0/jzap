package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/** Removes an arithmetic negation, so {@code -x} becomes {@code x}. PIT's INVERT_NEGS. */
public final class InvertNegsMutator implements Mutator {

    public static final String ID = "INVERT_NEGS";

    private static final Set<Integer> NEGATIONS =
            Set.of(Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitInsn(int opcode) {
                if (NEGATIONS.contains(opcode) && ctx.shouldMutate(ID, "removed negation")) {
                    // Dropping the instruction is the mutation: the value is left unnegated.
                    return;
                }
                super.visitInsn(opcode);
            }
        };
    }
}
