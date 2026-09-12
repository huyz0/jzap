package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Negates a local variable increment, so {@code i++} becomes {@code i--}. PIT's INCREMENTS. */
public final class IncrementsMutator implements Mutator {

    public static final String ID = "INCREMENTS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitIincInsn(int varIndex, int increment) {
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
