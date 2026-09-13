package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.MutationContext;
import io.github.huyz0.jzap.core.Mutator;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Replaces a whole method body: extreme mutation, as proposed by Niedermayr et al. and
 * implemented by Descartes.
 *
 * <p>Very coarse, and that is the point. One mutant per method instead of dozens, so the mutant
 * set shrinks by roughly an order of magnitude: on Apache Flink's core, Descartes generated 4,935
 * mutants in 14 minutes where PIT's default engine generated 43,619 in two and a half hours. What
 * survives still answers the question worth asking of a weak suite — is this method pseudo-tested,
 * covered but never actually checked?
 *
 * <p>Not part of the default set. It is selected explicitly, because it answers a different
 * question from the fine-grained mutators rather than a cheaper version of the same one.
 *
 * <p>Built on a {@link MethodNode} rather than a streaming visitor because the decision needs the
 * method's first line number, which arrives after the try/catch blocks that would have to be
 * discarded along with the body. Buffering the method makes that ordering irrelevant.
 */
abstract class ExtremeMutator implements Mutator {

    /** Whether this mutator handles a method with this return type. */
    protected abstract boolean applies(Type returnType);

    protected abstract String description(Type returnType);

    /** Emits the replacement body's return sequence. */
    protected abstract void emitBody(MethodVisitor mv, Type returnType);

    @Override
    public final MethodVisitor visit(MutationContext ctx, MethodVisitor next) {
        Type returnType = Type.getReturnType(ctx.descriptor());
        if (!applies(returnType) || isInitialiser(ctx.methodName())) {
            return next;
        }
        return new MethodNode(Opcodes.ASM9, 0, ctx.methodName(), ctx.descriptor(), null, null) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                int firstLine = firstLineOf(this);
                if (firstLine <= 0) {
                    // No debug information, so there is no line to report a mutant against.
                    if (next != null) {
                        accept(next);
                    }
                    return;
                }
                // Positioned before asking, rather than as a side effect of evaluating the
                // description argument. This mutator replaces a whole method, so it never passes
                // through the line tracker and has to say where it is itself.
                ctx.positionAtLine(firstLine);
                if (ctx.shouldMutate(id(), description(returnType))) {
                    instructions.clear();
                    tryCatchBlocks.clear();
                    localVariables = null;
                    visibleLocalVariableAnnotations = null;
                    invisibleLocalVariableAnnotations = null;
                    emitBody(this, returnType);
                }
                // Null downstream in collection mode, where nothing is being written out.
                if (next != null) {
                    accept(next);
                }
            }
        };
    }

    /**
     * Constructors are excluded. Emptying one skips the superclass constructor call, and the
     * class then fails verification: a non-viable mutant rather than a useful one.
     */
    private static boolean isInitialiser(String methodName) {
        return "<init>".equals(methodName) || "<clinit>".equals(methodName);
    }

    private static int firstLineOf(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
             insn = insn.getNext()) {
            if (insn instanceof LineNumberNode line) {
                return line.line;
            }
        }
        return 0;
    }

    /** Empties a void method. Descartes' void-method operator. */
    static final class VoidBody extends ExtremeMutator {

        static final String ID = "REMOVE_METHOD_BODY";

        @Override
        public String id() {
            return ID;
        }

        @Override
        protected boolean applies(Type returnType) {
            return returnType.getSort() == Type.VOID;
        }

        @Override
        protected String description(Type returnType) {
            return "removed the whole method body";
        }

        @Override
        protected void emitBody(MethodVisitor mv, Type returnType) {
            mv.visitInsn(Opcodes.RETURN);
        }
    }

    /** Replaces a value-returning method's body with a single default return. */
    static final class ConstantReturn extends ExtremeMutator {

        static final String ID = "CONSTANT_RETURN";

        @Override
        public String id() {
            return ID;
        }

        @Override
        protected boolean applies(Type returnType) {
            return returnType.getSort() != Type.VOID;
        }

        @Override
        protected String description(Type returnType) {
            return "replaced the whole method body with a single "
                    + defaultValueName(returnType) + " return";
        }

        private static String defaultValueName(Type returnType) {
            return switch (returnType.getSort()) {
                case Type.BOOLEAN -> "false";
                case Type.OBJECT, Type.ARRAY -> "null";
                default -> "0";
            };
        }

        @Override
        protected void emitBody(MethodVisitor mv, Type returnType) {
            switch (returnType.getSort()) {
                case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
                case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
                case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
                case Type.OBJECT, Type.ARRAY -> mv.visitInsn(Opcodes.ACONST_NULL);
                default -> mv.visitInsn(Opcodes.ICONST_0);
            }
            mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        }
    }
}
