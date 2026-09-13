package io.github.huyz0.jzap.core;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a conditional jump into a dispatch call that yields the branch condition.
 *
 * <p>One call carries both conditional mutators' ids, because a single jump is a mutation point
 * for each of them and the encoding has to decide once.
 */
final class Conditionals {

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<Integer, String> DESCRIPTORS = new HashMap<>();

    static {
        NAMES.put(Opcodes.IFEQ, "ifeq");
        DESCRIPTORS.put(Opcodes.IFEQ, "(III)Z");
        NAMES.put(Opcodes.IFNE, "ifne");
        DESCRIPTORS.put(Opcodes.IFNE, "(III)Z");
        NAMES.put(Opcodes.IFLT, "iflt");
        DESCRIPTORS.put(Opcodes.IFLT, "(III)Z");
        NAMES.put(Opcodes.IFLE, "ifle");
        DESCRIPTORS.put(Opcodes.IFLE, "(III)Z");
        NAMES.put(Opcodes.IFGT, "ifgt");
        DESCRIPTORS.put(Opcodes.IFGT, "(III)Z");
        NAMES.put(Opcodes.IFGE, "ifge");
        DESCRIPTORS.put(Opcodes.IFGE, "(III)Z");
        NAMES.put(Opcodes.IF_ICMPEQ, "icmpeq");
        DESCRIPTORS.put(Opcodes.IF_ICMPEQ, "(IIII)Z");
        NAMES.put(Opcodes.IF_ICMPNE, "icmpne");
        DESCRIPTORS.put(Opcodes.IF_ICMPNE, "(IIII)Z");
        NAMES.put(Opcodes.IF_ICMPLT, "icmplt");
        DESCRIPTORS.put(Opcodes.IF_ICMPLT, "(IIII)Z");
        NAMES.put(Opcodes.IF_ICMPLE, "icmple");
        DESCRIPTORS.put(Opcodes.IF_ICMPLE, "(IIII)Z");
        NAMES.put(Opcodes.IF_ICMPGT, "icmpgt");
        DESCRIPTORS.put(Opcodes.IF_ICMPGT, "(IIII)Z");
        NAMES.put(Opcodes.IF_ICMPGE, "icmpge");
        DESCRIPTORS.put(Opcodes.IF_ICMPGE, "(IIII)Z");
        NAMES.put(Opcodes.IF_ACMPEQ, "acmpeq");
        DESCRIPTORS.put(Opcodes.IF_ACMPEQ, "(Ljava/lang/Object;Ljava/lang/Object;II)Z");
        NAMES.put(Opcodes.IF_ACMPNE, "acmpne");
        DESCRIPTORS.put(Opcodes.IF_ACMPNE, "(Ljava/lang/Object;Ljava/lang/Object;II)Z");
        NAMES.put(Opcodes.IFNULL, "isnull");
        DESCRIPTORS.put(Opcodes.IFNULL, "(Ljava/lang/Object;II)Z");
        NAMES.put(Opcodes.IFNONNULL, "isnonnull");
        DESCRIPTORS.put(Opcodes.IFNONNULL, "(Ljava/lang/Object;II)Z");
    }

    private Conditionals() {
    }

    /** The dispatch method standing in for this jump, or null if there is none. */
    static String nameOf(int opcode) {
        return NAMES.get(opcode);
    }

    /** The dispatch method's descriptor, or null if there is none. */
    static String descriptorOf(int opcode) {
        return DESCRIPTORS.get(opcode);
    }

    static void emit(MethodVisitor mv, int opcode, int boundaryId, int negateId) {
        Bytecode.pushInt(mv, boundaryId);
        Bytecode.pushInt(mv, negateId);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SchemataTransformer.OPS,
                nameOf(opcode), descriptorOf(opcode), false);
    }
}
