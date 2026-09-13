package io.github.huyz0.jzap.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bytecode the agent should serve instead of what is on disk, keyed by JVM internal name
 * (e.g. {@code com/example/Foo}).
 *
 * <p>The controller process owns ASM and generates these bytes; the agent only installs
 * them. That inversion is what keeps the agent dependency-free: no bytecode library ever
 * reaches the JVM under test.
 *
 * <p>Nothing here keeps a copy of the original bytecode. Restoring a class means removing the
 * override and retransforming, at which point the JVM supplies the bytes it already had; holding
 * our own copy would mean retaining the class file of every class the analysis JVM ever loads --
 * the JDK, the test framework and the whole dependency tree included -- for the lifetime of a
 * process that is deliberately long-lived.
 */
public final class ClassOverrides {

    private static final Map<String, byte[]> OVERRIDES = new ConcurrentHashMap<>();

    private ClassOverrides() {
    }

    static byte[] lookup(String internalName) {
        return OVERRIDES.get(internalName);
    }

    /**
     * Installs replacement bytecode and makes it take effect immediately if the class is
     * already loaded. A class that is not yet loaded is picked up by the load-time
     * transformer instead, so both cases are covered by one call.
     */
    public static void install(String binaryName, byte[] bytes) {
        OVERRIDES.put(binaryName.replace('.', '/'), bytes);
        retransform(Set.of(binaryName));
    }

    /** Removes an override, restoring the class as it was originally loaded. */
    public static void remove(String binaryName) {
        if (OVERRIDES.remove(binaryName.replace('.', '/')) != null) {
            retransform(Set.of(binaryName));
        }
    }

    public static void removeAll() {
        Set<String> removed = new LinkedHashSet<>();
        for (String internal : OVERRIDES.keySet()) {
            if (OVERRIDES.remove(internal) != null) {
                removed.add(internal.replace('/', '.'));
            }
        }
        retransform(removed);
    }

    /**
     * Retransforms every loaded class with one of these binary names, in a single pass.
     *
     * <p>One pass rather than one per name because {@code getAllLoadedClasses} walks every class
     * in the JVM, and clearing a class's worth of mutants used to do that walk once per name.
     *
     * <p>Every match, not the first: the load-time transformer serves an override to whichever
     * loader asks for the name, so stopping at the first loaded copy would leave a second loader's
     * copy of the same class disagreeing with it about which mutant is installed.
     */
    private static void retransform(Set<String> binaryNames) {
        if (binaryNames.isEmpty()) {
            return;
        }
        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> c : JzapAgent.instrumentation().getAllLoadedClasses()) {
            if (binaryNames.contains(c.getName())) {
                loaded.add(c);
            }
        }
        if (loaded.isEmpty()) {
            return;
        }
        try {
            JzapAgent.instrumentation().retransformClasses(loaded.toArray(new Class<?>[0]));
        } catch (Exception e) {
            throw new IllegalStateException("cannot retransform " + binaryNames + ": " + e, e);
        }
    }
}
