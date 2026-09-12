package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Removes a call to a void method, discarding its arguments and receiver. PIT's
 * VOID_METHOD_CALLS.
 *
 * <p>Constructor calls are excluded: removing one leaves an uninitialised object on the
 * stack and the class fails verification, which produces a NON_VIABLE mutant rather than a
 * useful one.
 */
public final class VoidMethodCallsMutator implements Mutator {

    public static final String ID = "VOID_METHOD_CALLS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                        boolean isInterface) {
                boolean isVoid = Type.getReturnType(descriptor).getSort() == Type.VOID;
                boolean isConstructor = "<init>".equals(name);
                if (isVoid && !isConstructor
                        && ctx.shouldMutate(ID, "removed call to " + owner.replace('/', '.') + "::" + name)) {
                    discardOperands(opcode, descriptor);
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            private void discardOperands(int opcode, String descriptor) {
                Type[] args = Type.getArgumentTypes(descriptor);
                for (int i = args.length - 1; i >= 0; i--) {
                    super.visitInsn(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                }
                if (opcode != Opcodes.INVOKESTATIC) {
                    super.visitInsn(Opcodes.POP);
                }
            }
        };
    }
}
