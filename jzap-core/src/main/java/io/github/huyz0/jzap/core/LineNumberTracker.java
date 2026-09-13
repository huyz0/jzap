package io.github.huyz0.jzap.core;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Keeps {@link MutationContext#line()} current for the visitors downstream.
 *
 * <p>Belongs outermost in a visitor chain, so that every mutator and the schemata transformer see
 * the current line before deciding anything. A mutant with the wrong line is worse than no
 * mutant: it is reported against source that does not contain it.
 */
final class LineNumberTracker extends MethodVisitor {

    private final MutationContext ctx;

    LineNumberTracker(MutationContext ctx, MethodVisitor next) {
        super(Opcodes.ASM9, next);
        this.ctx = ctx;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        ctx.line(line);
        super.visitLineNumber(line, start);
    }
}
