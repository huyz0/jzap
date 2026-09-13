package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.ConditionalsBoundaryMutator;
import io.github.huyz0.jzap.core.mutator.EmptyReturnsMutator;
import io.github.huyz0.jzap.core.mutator.FalseReturnsMutator;
import io.github.huyz0.jzap.core.mutator.IncrementsMutator;
import io.github.huyz0.jzap.core.mutator.InvertNegsMutator;
import io.github.huyz0.jzap.core.mutator.MathMutator;
import io.github.huyz0.jzap.core.mutator.NegateConditionalsMutator;
import io.github.huyz0.jzap.core.mutator.PrimitiveReturnsMutator;
import io.github.huyz0.jzap.core.mutator.TrueReturnsMutator;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles every mutant of a class in at once, to be selected at run time by a field write.
 *
 * <p>This is mutant schemata, the technique behind Stryker's 20-70% and the reason PIT's own design
 * notes list it as the alternative it did not take. The cost it removes is class redefinition: per
 * mutant, the JVM currently has to discard a class, re-verify it and throw away its JIT-compiled
 * code. Under schemata the class is installed once and each mutant costs one volatile write.
 *
 * <h2>Branch-free, on purpose</h2>
 *
 * The obvious encoding guards each mutant with an {@code if} inside the mutated method. That adds
 * jumps, which adds stack map frames, which means {@code COMPUTE_FRAMES}, which means loading
 * classes to compute common supertypes -- and an analysis that fails whenever the transformer
 * cannot fully resolve the classpath.
 *
 * <p>So no jumps are added. Each mutable operation becomes a call to a static method in
 * {@link io.github.huyz0.jzap.agent.MutantOps} that takes the operands and the mutant ids and decides what to
 * return. The method's control flow is untouched, existing frames stay valid, and
 * {@code COMPUTE_MAXS} is enough. It also makes the size budget a non-issue: each site grows by
 * roughly the six bytes of a constant push and an invocation, so the 64KB method limit needs
 * thousands of mutation points in one method before it becomes a consideration.
 *
 * <h2>What it does not cover</h2>
 *
 * {@code VOID_METHOD_CALLS} removes a call, which cannot be expressed without a branch. Those
 * mutants fall back to per-mutant redefinition. A mutant that cannot be seeded here is routed, not
 * dropped -- silently losing mutants would show up as a smaller inventory rather than as a bug.
 */
final class SchemataTransformer {

    static final String OPS = "io/github/huyz0/jzap/agent/MutantOps";

    /** Mutators a schemata class can carry. Everything else falls back to redefinition. */
    private static final Set<String> SUPPORTED = Set.of(
            MathMutator.ID, IncrementsMutator.ID, InvertNegsMutator.ID,
            ConditionalsBoundaryMutator.ID, NegateConditionalsMutator.ID,
            TrueReturnsMutator.ID, FalseReturnsMutator.ID,
            PrimitiveReturnsMutator.ID, EmptyReturnsMutator.ID);

    /**
     * @param schemata    the transformed class
     * @param indices     schemata index per mutant compiled into it
     * @param fallbacks   mutants that must still be applied by redefinition
     */
    public record Result(byte[] schemata, Map<MutantKey, Integer> indices, List<Mutant> fallbacks) {
    }

    private SchemataTransformer() {
    }

    public static boolean supports(String mutatorId) {
        return SUPPORTED.contains(mutatorId);
    }

    /** Builds the schemata class for the mutants discovered in it. */
    public static Result transform(byte[] classBytes, List<Mutant> mutants) {
        Map<MutantKey, Integer> indices = new LinkedHashMap<>();
        List<Mutant> fallbacks = new ArrayList<>();
        int next = 0;
        for (Mutant mutant : mutants) {
            if (supports(mutant.key().mutator())) {
                indices.put(mutant.key(), next++);
            } else {
                fallbacks.add(mutant);
            }
        }
        if (indices.isEmpty()) {
            return new Result(classBytes, Map.of(), fallbacks);
        }

        ClassReader reader = new ClassReader(classBytes);
        // COMPUTE_MAXS only: no control flow is added, so the frames already in the class remain
        // correct and no class loading is needed to recompute them.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        MutationContext ctx = MutationContext.schemata(
                reader.getClassName().replace('/', '.'), indices);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {

            @Override
            public void visitSource(String source, String debug) {
                ctx.sourceFile(source);
                ctx.sourceMap(SourceMap.parse(debug));
                super.visitSource(source, debug);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor writerVisitor =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!MethodFilter.shouldMutate(access, name)) {
                    return writerVisitor;
                }
                ctx.enterMethod(name, descriptor);
                // The loop guard rides along here for the same reason it does on a seeded mutant:
                // a schemata class is what the mutated code actually runs as, so without it a
                // runaway mutant falls through to the wall-clock backstop and the verdict stops
                // being deterministic.
                return new LineNumberTracker(ctx, new SchemataMethodVisitor(
                        ctx, descriptor, new BackEdgeInstrumenter(writerVisitor)));
            }
        }, ClassReader.EXPAND_FRAMES);
        return new Result(writer.toByteArray(), indices, fallbacks);
    }

    /**
     * Rewrites each mutable operation into a dispatch call.
     *
     * <p>Registers keys through {@link MutationContext} rather than counting its own ordinals, and
     * consults the mutators' own opcode tables rather than copying them, so it cannot drift from
     * what discovery found.
     */
    private static final class SchemataMethodVisitor extends MethodVisitor {

        private final MutationContext ctx;
        private final Type returnType;

        /**
         * The instruction before the current one, when it was a constant push.
         *
         * <p>This has to be tracked exactly as {@code ReturnValueMutator} tracks it. Both use it
         * to ask a return mutator whether its mutation would be a no-op, and a mutator that
         * declines also declines to take an ordinal -- so a visitor here that forgot the previous
         * instruction on a different set of opcodes would shift every later ordinal in the method
         * and seed one mutant under another's key. Any callback one of them overrides, the other
         * must too; SchemataVisitorParityTest holds them to it.
         */
        private int lastOpcode = -1;
        private Object lastConstant;

        SchemataMethodVisitor(MutationContext ctx, String descriptor, MethodVisitor next) {
            super(Opcodes.ASM9, next);
            this.ctx = ctx;
            this.returnType = Type.getReturnType(descriptor);
        }

        private void forget() {
            lastOpcode = -1;
            lastConstant = null;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == returnType.getOpcode(Opcodes.IRETURN) && emitReturnDispatch()) {
                forget();
                super.visitInsn(opcode);
                return;
            }
            if (MathMutator.handles(opcode)) {
                int id = ctx.schemataIndex(ctx.register(MathMutator.ID));
                if (id >= 0) {
                    emitMath(opcode, id);
                    forget();
                    return;
                }
            } else if (InvertNegsMutator.handles(opcode)) {
                int id = ctx.schemataIndex(ctx.register(InvertNegsMutator.ID));
                if (id >= 0) {
                    emitNegation(opcode, id);
                    forget();
                    return;
                }
            }
            int previous = lastOpcode;
            Object previousConstant = lastConstant;
            lastOpcode = opcode;
            lastConstant = previous == opcode ? previousConstant : null;
            super.visitInsn(opcode);
        }

        /** @return true when a dispatch call was emitted in place of the plain return value */
        private boolean emitReturnDispatch() {
            return ReturnRules.emitSchemata(ctx, mv, returnType, lastOpcode, lastConstant);
        }

        private void emitMath(int opcode, int id) {
            String name = MathNames.of(opcode);
            Type type = MathNames.typeOf(opcode);
            String descriptor = MathNames.isShiftWithIntDistance(opcode)
                    ? "(" + type.getDescriptor() + "II)" + type.getDescriptor()
                    : "(" + type.getDescriptor() + type.getDescriptor() + "I)" + type.getDescriptor();
            Bytecode.pushInt(mv, id);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, name, descriptor, false);
        }

        private void emitNegation(int opcode, int id) {
            Type type = switch (opcode) {
                case Opcodes.LNEG -> Type.LONG_TYPE;
                case Opcodes.FNEG -> Type.FLOAT_TYPE;
                case Opcodes.DNEG -> Type.DOUBLE_TYPE;
                default -> Type.INT_TYPE;
            };
            String name = switch (opcode) {
                case Opcodes.LNEG -> "lneg";
                case Opcodes.FNEG -> "fneg";
                case Opcodes.DNEG -> "dneg";
                default -> "ineg";
            };
            Bytecode.pushInt(mv, id);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, name,
                    "(" + type.getDescriptor() + "I)" + type.getDescriptor(), false);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            boolean boundary = ConditionalsBoundaryMutator.handles(opcode);
            boolean negate = NegateConditionalsMutator.handles(opcode);
            if (!boundary && !negate) {
                forget();
                super.visitJumpInsn(opcode, label);
                return;
            }
            // Registered in the same order the mutating pass registers them, so the ordinals
            // agree even though one visitor here does the work of two mutators.
            int boundaryId = boundary
                    ? ctx.schemataIndex(ctx.register(ConditionalsBoundaryMutator.ID))
                    : -1;
            int negateId = negate
                    ? ctx.schemataIndex(ctx.register(NegateConditionalsMutator.ID))
                    : -1;
            forget();
            if (boundaryId < 0 && negateId < 0) {
                super.visitJumpInsn(opcode, label);
                return;
            }
            Conditionals.emit(mv, opcode, boundaryId, negateId);
            // The dispatch returns the branch condition, so the jump becomes "branch if true".
            super.visitJumpInsn(Opcodes.IFNE, label);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            int id = ctx.schemataIndex(ctx.register(IncrementsMutator.ID));
            forget();
            if (id < 0) {
                super.visitIincInsn(varIndex, increment);
                return;
            }
            // IINC has no operand stack form, so it becomes load, dispatch, store.
            mv.visitVarInsn(Opcodes.ILOAD, varIndex);
            Bytecode.pushInt(mv, increment);
            Bytecode.pushInt(mv, id);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "increment", "(III)I", false);
            mv.visitVarInsn(Opcodes.ISTORE, varIndex);
        }

        @Override
        public void visitLdcInsn(Object value) {
            lastOpcode = Opcodes.LDC;
            lastConstant = value;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            lastOpcode = opcode;
            lastConstant = operand;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitLabel(Label label) {
            forget();
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            forget();
            super.visitFrame(type, numLocal, local, numStack, stack);
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
            forget();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            forget();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle,
                                           Object... arguments) {
            forget();
            super.visitInvokeDynamicInsn(name, descriptor, handle, arguments);
        }
    }
}
