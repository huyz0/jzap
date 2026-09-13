package io.github.huyz0.jzap.core;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Small emission helpers shared by the instrumenters. */
final class Bytecode {

    private Bytecode() {
    }

    static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }
}
