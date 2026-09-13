package io.github.huyz0.jzap.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps an arithmetic opcode to the {@link io.github.huyz0.jzap.agent.MutantOps} method that stands in for it.
 *
 * <p>Names follow the opcode mnemonics, so the mapping is checkable by reading rather than by
 * remembering.
 */
final class MathNames {

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<Integer, Type> TYPES = new HashMap<>();

    static {
        NAMES.put(Opcodes.IADD, "iadd");
        TYPES.put(Opcodes.IADD, Type.INT_TYPE);
        NAMES.put(Opcodes.ISUB, "isub");
        TYPES.put(Opcodes.ISUB, Type.INT_TYPE);
        NAMES.put(Opcodes.IMUL, "imul");
        TYPES.put(Opcodes.IMUL, Type.INT_TYPE);
        NAMES.put(Opcodes.IDIV, "idiv");
        TYPES.put(Opcodes.IDIV, Type.INT_TYPE);
        NAMES.put(Opcodes.IREM, "irem");
        TYPES.put(Opcodes.IREM, Type.INT_TYPE);
        NAMES.put(Opcodes.IAND, "iand");
        TYPES.put(Opcodes.IAND, Type.INT_TYPE);
        NAMES.put(Opcodes.IOR, "ior");
        TYPES.put(Opcodes.IOR, Type.INT_TYPE);
        NAMES.put(Opcodes.IXOR, "ixor");
        TYPES.put(Opcodes.IXOR, Type.INT_TYPE);
        NAMES.put(Opcodes.ISHL, "ishl");
        TYPES.put(Opcodes.ISHL, Type.INT_TYPE);
        NAMES.put(Opcodes.ISHR, "ishr");
        TYPES.put(Opcodes.ISHR, Type.INT_TYPE);
        NAMES.put(Opcodes.IUSHR, "iushr");
        TYPES.put(Opcodes.IUSHR, Type.INT_TYPE);
        NAMES.put(Opcodes.LADD, "ladd");
        TYPES.put(Opcodes.LADD, Type.LONG_TYPE);
        NAMES.put(Opcodes.LSUB, "lsub");
        TYPES.put(Opcodes.LSUB, Type.LONG_TYPE);
        NAMES.put(Opcodes.LMUL, "lmul");
        TYPES.put(Opcodes.LMUL, Type.LONG_TYPE);
        NAMES.put(Opcodes.LDIV, "ldiv");
        TYPES.put(Opcodes.LDIV, Type.LONG_TYPE);
        NAMES.put(Opcodes.LREM, "lrem");
        TYPES.put(Opcodes.LREM, Type.LONG_TYPE);
        NAMES.put(Opcodes.LAND, "land");
        TYPES.put(Opcodes.LAND, Type.LONG_TYPE);
        NAMES.put(Opcodes.LOR, "lor");
        TYPES.put(Opcodes.LOR, Type.LONG_TYPE);
        NAMES.put(Opcodes.LXOR, "lxor");
        TYPES.put(Opcodes.LXOR, Type.LONG_TYPE);
        NAMES.put(Opcodes.LSHL, "lshl");
        TYPES.put(Opcodes.LSHL, Type.LONG_TYPE);
        NAMES.put(Opcodes.LSHR, "lshr");
        TYPES.put(Opcodes.LSHR, Type.LONG_TYPE);
        NAMES.put(Opcodes.LUSHR, "lushr");
        TYPES.put(Opcodes.LUSHR, Type.LONG_TYPE);
        NAMES.put(Opcodes.FADD, "fadd");
        TYPES.put(Opcodes.FADD, Type.FLOAT_TYPE);
        NAMES.put(Opcodes.FSUB, "fsub");
        TYPES.put(Opcodes.FSUB, Type.FLOAT_TYPE);
        NAMES.put(Opcodes.FMUL, "fmul");
        TYPES.put(Opcodes.FMUL, Type.FLOAT_TYPE);
        NAMES.put(Opcodes.FDIV, "fdiv");
        TYPES.put(Opcodes.FDIV, Type.FLOAT_TYPE);
        NAMES.put(Opcodes.FREM, "frem");
        TYPES.put(Opcodes.FREM, Type.FLOAT_TYPE);
        NAMES.put(Opcodes.DADD, "dadd");
        TYPES.put(Opcodes.DADD, Type.DOUBLE_TYPE);
        NAMES.put(Opcodes.DSUB, "dsub");
        TYPES.put(Opcodes.DSUB, Type.DOUBLE_TYPE);
        NAMES.put(Opcodes.DMUL, "dmul");
        TYPES.put(Opcodes.DMUL, Type.DOUBLE_TYPE);
        NAMES.put(Opcodes.DDIV, "ddiv");
        TYPES.put(Opcodes.DDIV, Type.DOUBLE_TYPE);
        NAMES.put(Opcodes.DREM, "drem");
        TYPES.put(Opcodes.DREM, Type.DOUBLE_TYPE);
    }

    private MathNames() {
    }

    static String of(int opcode) {
        return NAMES.get(opcode);
    }

    static Type typeOf(int opcode) {
        return TYPES.get(opcode);
    }

    /** Long shifts take an int distance, so their descriptor is not the symmetric one. */
    static boolean isShiftWithIntDistance(int opcode) {
        return opcode == Opcodes.LSHL || opcode == Opcodes.LSHR || opcode == Opcodes.LUSHR;
    }
}
