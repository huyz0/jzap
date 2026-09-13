package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
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
 *
 * <p>A mutant that replaces a value with the value already there is suppressed. Such a mutant
 * is equivalent to the original by construction, so it can never be killed and would be
 * reported as surviving forever — a permanent false alarm in every report. PIT suppresses the
 * same case, which was established by running its mutators over a probe class rather than
 * assumed; see tools/parity/parity-baseline.yaml.
 */
abstract class ReturnValueMutator implements Mutator {

    protected abstract boolean applies(Type returnType);

    protected abstract String description(Type returnType);

    /** Emits the replacement value into {@code mv}, with the original already discarded. */
    protected abstract void pushReplacement(MethodVisitor mv, Type returnType);

    /**
     * Whether the value about to be returned is already this mutator's replacement value, making
     * the mutation a no-op.
     *
     * @param preceding what produced that value, as far as it can be named. A label or a frame
     *                  clears it, because the value then arrives from a merge of several paths and
     *                  nothing can be concluded about it.
     */
    protected boolean wouldBeNoOp(Type returnType, PrecedingValue preceding) {
        return false;
    }

    @Override
    public final MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        Type returnType = Type.getReturnType(ctx.descriptor());
        if (!applies(returnType)) {
            return next;
        }
        int returnOpcode = returnType.getOpcode(Opcodes.IRETURN);
        return new ReturnMutatingMethodVisitor(next, ctx, returnType, returnOpcode);
    }

    /** Tracks the preceding instruction so no-op mutations can be recognised and skipped. */
    private final class ReturnMutatingMethodVisitor extends MethodVisitor {

        private final MutationContext ctx;
        private final Type returnType;
        private final int returnOpcode;
        private final PrecedingValue preceding = new PrecedingValue();

        ReturnMutatingMethodVisitor(MethodVisitor next, MutationContext ctx, Type returnType,
                                    int returnOpcode) {
            super(Opcodes.ASM9, next);
            this.ctx = ctx;
            this.returnType = returnType;
            this.returnOpcode = returnOpcode;
        }

        private void forget() {
            preceding.forget();
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == returnOpcode) {
                mutateOrPassThrough(opcode);
                return;
            }
            preceding.insn(opcode);
            super.visitInsn(opcode);
        }

        private void mutateOrPassThrough(int opcode) {
            if (wouldBeNoOp(returnType, preceding)) {
                // Deliberately does not register a mutant, so discovery and application agree.
                forget();
                super.visitInsn(opcode);
                return;
            }
            if (!ctx.shouldMutate(id(), description(returnType))) {
                forget();
                super.visitInsn(opcode);
                return;
            }
            // Write straight to the delegate, so the instructions making up the replacement
            // value are not re-examined by this same visitor.
            MethodVisitor out = this.mv;
            out.visitInsn(returnType.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            pushReplacement(out, returnType);
            out.visitInsn(returnOpcode);
            forget();
        }

        @Override
        public void visitLdcInsn(Object value) {
            preceding.constantPush(Opcodes.LDC, value);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitLabel(Label label) {
            forget();   // a merge point: the returned value no longer has a single source
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            forget();
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            preceding.constantPush(opcode, operand);
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            forget();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            forget();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            preceding.field(opcode, owner, name, descriptor);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            preceding.call(opcode, owner, name, descriptor);
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle,
                                           Object... arguments) {
            forget();
            super.visitInvokeDynamicInsn(name, descriptor, handle, arguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            forget();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            forget();
            super.visitIincInsn(varIndex, increment);
        }
    }
}
