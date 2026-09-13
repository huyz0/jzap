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

    public static boolean appliesTo(Type returnType) {
        return switch (returnType.getSort()) {
            case Type.BYTE, Type.SHORT, Type.CHAR, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> true;
            default -> false;
        };
    }

    public static boolean isNoOp(int lastOpcode) {
        return switch (lastOpcode) {
            case Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 -> true;
            default -> false;
        };
    }

    @Override
    protected boolean applies(Type returnType) {
        return appliesTo(returnType);
    }

    @Override
    protected String description(Type returnType) {
        return "replaced " + returnType.getClassName() + " return with 0";
    }

    @Override
    protected boolean wouldBeNoOp(Type returnType, PrecedingValue preceding) {
        return isNoOp(preceding.opcode());
    }

    @Override
    protected void pushReplacement(MethodVisitor mv, Type returnType) {
        pushZero(mv, returnType);
    }

    /** Pushes the zero of this numeric type. */
    public static void pushZero(MethodVisitor mv, Type returnType) {
        switch (returnType.getSort()) {
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            default -> mv.visitInsn(Opcodes.ICONST_0);
        }
    }
}
