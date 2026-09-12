package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/** Swaps an arithmetic or bitwise operator for a different one. Equivalent to PIT's MATH. */
public final class MathMutator implements Mutator {

    public static final String ID = "MATH";

    private static final Map<Integer, Integer> REPLACEMENTS = new HashMap<>();
    private static final Map<Integer, String> DESCRIPTIONS = new HashMap<>();

    private static void pair(int from, int to, String desc) {
        REPLACEMENTS.put(from, to);
        DESCRIPTIONS.put(from, desc);
    }

    static {
        pair(Opcodes.IADD, Opcodes.ISUB, "replaced integer addition with subtraction");
        pair(Opcodes.ISUB, Opcodes.IADD, "replaced integer subtraction with addition");
        pair(Opcodes.IMUL, Opcodes.IDIV, "replaced integer multiplication with division");
        pair(Opcodes.IDIV, Opcodes.IMUL, "replaced integer division with multiplication");
        pair(Opcodes.IREM, Opcodes.IMUL, "replaced integer modulus with multiplication");
        pair(Opcodes.IAND, Opcodes.IOR, "replaced bitwise AND with OR");
        pair(Opcodes.IOR, Opcodes.IAND, "replaced bitwise OR with AND");
        pair(Opcodes.IXOR, Opcodes.IAND, "replaced XOR with AND");
        pair(Opcodes.ISHL, Opcodes.ISHR, "replaced shift left with shift right");
        pair(Opcodes.ISHR, Opcodes.ISHL, "replaced shift right with shift left");
        pair(Opcodes.IUSHR, Opcodes.ISHL, "replaced unsigned shift right with shift left");

        pair(Opcodes.LADD, Opcodes.LSUB, "replaced long addition with subtraction");
        pair(Opcodes.LSUB, Opcodes.LADD, "replaced long subtraction with addition");
        pair(Opcodes.LMUL, Opcodes.LDIV, "replaced long multiplication with division");
        pair(Opcodes.LDIV, Opcodes.LMUL, "replaced long division with multiplication");
        pair(Opcodes.LREM, Opcodes.LMUL, "replaced long modulus with multiplication");
        pair(Opcodes.LAND, Opcodes.LOR, "replaced bitwise AND with OR");
        pair(Opcodes.LOR, Opcodes.LAND, "replaced bitwise OR with AND");
        pair(Opcodes.LXOR, Opcodes.LAND, "replaced XOR with AND");
        pair(Opcodes.LSHL, Opcodes.LSHR, "replaced shift left with shift right");
        pair(Opcodes.LSHR, Opcodes.LSHL, "replaced shift right with shift left");
        pair(Opcodes.LUSHR, Opcodes.LSHL, "replaced unsigned shift right with shift left");

        pair(Opcodes.FADD, Opcodes.FSUB, "replaced float addition with subtraction");
        pair(Opcodes.FSUB, Opcodes.FADD, "replaced float subtraction with addition");
        pair(Opcodes.FMUL, Opcodes.FDIV, "replaced float multiplication with division");
        pair(Opcodes.FDIV, Opcodes.FMUL, "replaced float division with multiplication");
        pair(Opcodes.FREM, Opcodes.FMUL, "replaced float modulus with multiplication");

        pair(Opcodes.DADD, Opcodes.DSUB, "replaced double addition with subtraction");
        pair(Opcodes.DSUB, Opcodes.DADD, "replaced double subtraction with addition");
        pair(Opcodes.DMUL, Opcodes.DDIV, "replaced double multiplication with division");
        pair(Opcodes.DDIV, Opcodes.DMUL, "replaced double division with multiplication");
        pair(Opcodes.DREM, Opcodes.DMUL, "replaced double modulus with multiplication");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitInsn(int opcode) {
                Integer replacement = REPLACEMENTS.get(opcode);
                if (replacement != null && ctx.shouldMutate(ID, DESCRIPTIONS.get(opcode))) {
                    super.visitInsn(replacement);
                } else {
                    super.visitInsn(opcode);
                }
            }
        };
    }
}
