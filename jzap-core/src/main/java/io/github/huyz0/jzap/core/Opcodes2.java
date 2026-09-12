package io.github.huyz0.jzap.core;

import org.objectweb.asm.Type;

/** Small bytecode helpers shared by the mutators. */
final class Opcodes2 {

    private Opcodes2() {
    }

    /** Stack slots a value of this type occupies: 2 for long and double, else 1. */
    static int slots(Type type) {
        return type.getSize();
    }

    static String simpleName(Type type) {
        String n = type.getClassName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }
}
