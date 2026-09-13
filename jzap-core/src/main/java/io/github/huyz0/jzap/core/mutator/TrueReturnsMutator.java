package io.github.huyz0.jzap.core.mutator;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Makes a boolean-returning method return true. PIT's TRUE_RETURNS. */
public final class TrueReturnsMutator extends ReturnValueMutator {

    public static final String ID = "TRUE_RETURNS";

    @Override
    public String id() {
        return ID;
    }

    public static boolean appliesTo(Type returnType) {
        return returnType.getSort() == Type.BOOLEAN;
    }

    public static boolean isNoOp(int lastOpcode) {
        return lastOpcode == Opcodes.ICONST_1;
    }

    @Override
    protected boolean applies(Type returnType) {
        return appliesTo(returnType);
    }

    @Override
    protected String description(Type returnType) {
        return "replaced boolean return with true";
    }

    @Override
    protected boolean wouldBeNoOp(int opcode, Object constant) {
        return isNoOp(opcode);   // the method already returns true here
    }

    @Override
    protected void pushReplacement(MethodVisitor mv, Type returnType) {
        mv.visitInsn(Opcodes.ICONST_1);
    }
}
