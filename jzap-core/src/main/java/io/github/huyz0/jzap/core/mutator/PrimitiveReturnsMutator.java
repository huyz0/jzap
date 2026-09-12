package io.github.huyz0.jzap.core.mutator;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Makes a numeric-returning method return zero. PIT's PRIMITIVE_RETURNS.
 *
 * <p>Boolean is excluded because {@link TrueReturnsMutator} and {@link FalseReturnsMutator}
 * already cover it; mutating it here as well would produce a duplicate mutant.
 */
public final class PrimitiveReturnsMutator extends ReturnValueMutator {

    public static final String ID = "PRIMITIVE_RETURNS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected boolean applies(Type returnType) {
        return switch (returnType.getSort()) {
            case Type.BYTE, Type.SHORT, Type.CHAR, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> true;
            default -> false;
        };
    }

    @Override
    protected String description(Type returnType) {
        return "replaced " + returnType.getClassName() + " return with 0";
    }

    @Override
    protected boolean wouldBeNoOp(int opcode, Object constant) {
        return switch (returnTypeZeroOpcode(opcode)) {
            case 1 -> true;
            default -> false;
        };
    }

    /** 1 when the preceding instruction already pushed a zero of some numeric type. */
    private static int returnTypeZeroOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 -> 1;
            default -> 0;
        };
    }

    @Override
    protected void pushReplacement(MethodVisitor mv, Type returnType) {
        switch (returnType.getSort()) {
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            default -> mv.visitInsn(Opcodes.ICONST_0);
        }
    }
}
