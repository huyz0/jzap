package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/** Inverts a conditional. Equivalent to PIT's NEGATE_CONDITIONALS. */
public final class NegateConditionalsMutator implements Mutator {

    public static final String ID = "NEGATE_CONDITIONALS";

    private static final Map<Integer, Integer> REPLACEMENTS = new HashMap<>();
    private static final Map<Integer, String> DESCRIPTIONS = new HashMap<>();

    private static void pair(int from, int to, String desc) {
        REPLACEMENTS.put(from, to);
        DESCRIPTIONS.put(from, desc);
    }

    static {
        pair(Opcodes.IFEQ, Opcodes.IFNE, "negated conditional: == became !=");
        pair(Opcodes.IFNE, Opcodes.IFEQ, "negated conditional: != became ==");
        pair(Opcodes.IFLE, Opcodes.IFGT, "negated conditional: <= became >");
        pair(Opcodes.IFGE, Opcodes.IFLT, "negated conditional: >= became <");
        pair(Opcodes.IFGT, Opcodes.IFLE, "negated conditional: > became <=");
        pair(Opcodes.IFLT, Opcodes.IFGE, "negated conditional: < became >=");
        pair(Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, "negated conditional: == became !=");
        pair(Opcodes.IF_ICMPNE, Opcodes.IF_ICMPEQ, "negated conditional: != became ==");
        pair(Opcodes.IF_ICMPLE, Opcodes.IF_ICMPGT, "negated conditional: <= became >");
        pair(Opcodes.IF_ICMPGE, Opcodes.IF_ICMPLT, "negated conditional: >= became <");
        pair(Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, "negated conditional: > became <=");
        pair(Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, "negated conditional: < became >=");
        pair(Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, "negated conditional: == became !=");
        pair(Opcodes.IF_ACMPNE, Opcodes.IF_ACMPEQ, "negated conditional: != became ==");
        pair(Opcodes.IFNULL, Opcodes.IFNONNULL, "negated conditional: == null became != null");
        pair(Opcodes.IFNONNULL, Opcodes.IFNULL, "negated conditional: != null became == null");
    }

    /** Whether this mutator seeds a mutant at this opcode, so filters can match its ordinals. */
    public static boolean handles(int opcode) {
        return REPLACEMENTS.containsKey(opcode);
    }

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
