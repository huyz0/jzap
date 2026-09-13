package io.github.huyz0.jzap.core;

import org.objectweb.asm.Opcodes;

/**
 * Decides which methods are worth mutating.
 *
 * <p>The rules are stated here rather than scattered through the mutators because they are
 * a source of inventory differences against PIT, and docs/parity-and-benchmarks.md requires
 * every such difference to have a named cause.
 */
final class MethodFilter {

    private MethodFilter() {
    }

    public static boolean shouldMutate(int access, String name) {
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;   // no code to mutate
        }
        if ((access & Opcodes.ACC_BRIDGE) != 0) {
            return false;   // a compiler-generated forwarder; a mutant here is junk
        }
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
            // Lambda bodies are synthetic but contain real user code, so they are kept.
            // Other synthetics (switch maps, access$ bridges, assertions scaffolding) are not.
            return name.startsWith("lambda$");
        }
        return true;
    }
}
