package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Base for the four return-value mutators, which between them cover what PIT splits into
 * TRUE_RETURNS, FALSE_RETURNS, PRIMITIVE_RETURNS and EMPTY_RETURNS.
 *
 * <p>Each subclass decides whether it applies to a method's return type and emits the
 * replacement value. The original value is discarded first, so the stack shape at the return
 * instruction is unchanged and existing stack map frames stay valid.
 */
abstract class ReturnValueMutator implements Mutator {

    /** Whether this mutator produces a mutant for a method with this return type. */
    protected abstract boolean applies(Type returnType);

    protected abstract String description(Type returnType);

    /** Emits the replacement value into {@code mv}, with the original already discarded. */
    protected abstract void pushReplacement(MethodVisitor mv, Type returnType);

    @Override
    public final MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        Type returnType = Type.getReturnType(ctx.descriptor());
        if (!applies(returnType)) {
            return next;
        }
        int returnOpcode = returnType.getOpcode(Opcodes.IRETURN);
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode != returnOpcode || !ctx.shouldMutate(id(), description(returnType))) {
                    super.visitInsn(opcode);
                    return;
                }
                // Write straight to the delegate, so the instructions making up the
                // replacement value are not re-examined by this same visitor.
                MethodVisitor out = this.mv;
                out.visitInsn(returnType.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                pushReplacement(out, returnType);
                out.visitInsn(returnOpcode);
            }
        };
    }
}
