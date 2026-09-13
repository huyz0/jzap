package io.github.huyz0.jzap.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Inserts a probe call at the start of every source line of a target class.
 *
 * <p>Line granularity, not basic-block granularity. Block-level coverage with
 * exception-correct attribution is the better design — it is where PIT's own PR #534 goes —
 * and it is scheduled for M10 in docs/delivery-plan.md. Line granularity is sound but selects
 * more tests than strictly necessary.
 *
 * <p>Probes are emitted lazily, immediately before the first real instruction of a line,
 * rather than at the point the line number is visited. ASM reports a label, then its line
 * number, and only then the stack map frame for that offset; injecting at the line number
 * would therefore place instructions between a branch target and its frame, which produces a
 * class the verifier rejects and, worse, code that misbehaves before it is rejected.
 */
public final class CoverageInstrumenter {

    static final String RECORDER = "io/github/huyz0/jzap/agent/CoverageRecorder";

    private final ProbeIndex index;

    public CoverageInstrumenter(ProbeIndex index) {
        this.index = index;
    }

    public byte[] instrument(String binaryName, byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return mv;
                }
                // Back-edge counting rides along with coverage so the baseline iteration count
                // is measured from the same run, on the same code, as everything else.
                return new ProbeInsertingMethodVisitor(new BackEdgeInstrumenter(mv), binaryName, index);
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /** Defers each probe until just before the next instruction, so frames stay put. */
    private static final class ProbeInsertingMethodVisitor extends MethodVisitor {

        private final String binaryName;
        private final ProbeIndex index;
        private int pendingProbe = -1;

        ProbeInsertingMethodVisitor(MethodVisitor next, String binaryName, ProbeIndex index) {
            super(Opcodes.ASM9, next);
            this.binaryName = binaryName;
            this.index = index;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            pendingProbe = index.allocate(binaryName, line);
        }

        /** Labels and frames mark positions, so a pending probe must not be flushed before them. */
        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        private void flush() {
            if (pendingProbe < 0) {
                return;
            }
            int probeId = pendingProbe;
            pendingProbe = -1;
            pushInt(mv, probeId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "hit", "(I)V", false);
        }

        @Override
        public void visitInsn(int opcode) {
            flush();
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            flush();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            flush();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            flush();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            flush();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            flush();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle,
                                           Object... arguments) {
            flush();
            super.visitInvokeDynamicInsn(name, descriptor, handle, arguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            flush();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            flush();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            flush();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            flush();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            flush();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            flush();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            pendingProbe = -1;  // a line with no instructions after it needs no probe
            super.visitMaxs(maxStack, maxLocals);
        }
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }
}
