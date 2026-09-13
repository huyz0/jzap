package io.github.huyz0.jzap.core;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * Inserts a {@link io.github.huyz0.jzap.agent.LoopGuard#tick()} call at every loop back edge.
 *
 * <p>A back edge is a jump to a label already emitted, which is exactly what a loop is at the
 * bytecode level, whatever source construct produced it. Detecting them by that definition rather
 * than by recognising {@code for} and {@code while} means {@code do/while}, labelled breaks and
 * anything a compiler invents are all covered without enumerating them.
 */
final class BackEdgeInstrumenter extends MethodVisitor {

    static final String GUARD = "io/github/huyz0/jzap/agent/LoopGuard";

    private final Set<Label> emitted = new HashSet<>();

    BackEdgeInstrumenter(MethodVisitor next) {
        super(Opcodes.ASM9, next);
    }

    @Override
    public void visitLabel(Label label) {
        emitted.add(label);
        super.visitLabel(label);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (emitted.contains(label)) {
            // Emitted before the jump itself, so the count rises on every iteration including
            // the one that would otherwise run forever.
            super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "tick", "()V", false);
        }
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        guardIfAnyIsBackEdge(dflt, labels);
        super.visitTableSwitchInsn(min, max, dflt, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        guardIfAnyIsBackEdge(dflt, labels);
        super.visitLookupSwitchInsn(dflt, keys, labels);
    }

    private void guardIfAnyIsBackEdge(Label dflt, Label[] labels) {
        if (emitted.contains(dflt)) {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "tick", "()V", false);
            return;
        }
        for (Label label : labels) {
            if (emitted.contains(label)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "tick", "()V", false);
                return;
            }
        }
    }
}
