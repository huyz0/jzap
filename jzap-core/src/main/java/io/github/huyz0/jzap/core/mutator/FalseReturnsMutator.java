package io.github.huyz0.jzap.core.mutator;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Makes a boolean-returning method return false. PIT's FALSE_RETURNS. */
public final class FalseReturnsMutator extends ReturnValueMutator {

    public static final String ID = "FALSE_RETURNS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected boolean applies(Type returnType) {
        return returnType.getSort() == Type.BOOLEAN;
    }

    @Override
    protected String description(Type returnType) {
        return "replaced boolean return with false";
    }

    @Override
    protected boolean wouldBeNoOp(int opcode, Object constant) {
        return opcode == Opcodes.ICONST_0;   // the method already returns false here
    }

    @Override
    protected void pushReplacement(MethodVisitor mv, Type returnType) {
        mv.visitInsn(Opcodes.ICONST_0);
    }
}
