package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * Replaces a relational operator with the one differing only at the boundary, e.g.
 * {@code <} becomes {@code <=}. Equivalent to PIT's CONDITIONALS_BOUNDARY.
 */
public final class ConditionalsBoundaryMutator implements Mutator {

    public static final String ID = "CONDITIONALS_BOUNDARY";

    private static final Map<Integer, Integer> REPLACEMENTS = Map.of(
            Opcodes.IFLE, Opcodes.IFLT,
            Opcodes.IFGE, Opcodes.IFGT,
            Opcodes.IFGT, Opcodes.IFGE,
            Opcodes.IFLT, Opcodes.IFLE,
            Opcodes.IF_ICMPLE, Opcodes.IF_ICMPLT,
            Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPLT, Opcodes.IF_ICMPLE);

    private static final Map<Integer, String> DESCRIPTIONS = Map.of(
            Opcodes.IFLE, "changed conditional boundary: <= became <",
            Opcodes.IFGE, "changed conditional boundary: >= became >",
            Opcodes.IFGT, "changed conditional boundary: > became >=",
            Opcodes.IFLT, "changed conditional boundary: < became <=",
            Opcodes.IF_ICMPLE, "changed conditional boundary: <= became <",
            Opcodes.IF_ICMPGE, "changed conditional boundary: >= became >",
            Opcodes.IF_ICMPGT, "changed conditional boundary: > became >=",
            Opcodes.IF_ICMPLT, "changed conditional boundary: < became <=");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitJumpInsn(int opcode, Label label) {
                Integer replacement = REPLACEMENTS.get(opcode);
                if (replacement != null && ctx.shouldMutate(ID, DESCRIPTIONS.get(opcode))) {
                    super.visitJumpInsn(replacement, label);
                } else {
                    super.visitJumpInsn(opcode, label);
                }
            }
        };
    }
}
