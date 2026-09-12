package io.github.huyz0.jzap.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bytecode the agent should serve instead of what is on disk, keyed by JVM internal name
 * (e.g. {@code com/example/Foo}).
 *
 * <p>The controller process owns ASM and generates these bytes; the agent only installs
 * them. That inversion is what keeps the agent dependency-free: no bytecode library ever
 * reaches the JVM under test.
 */
public final class ClassOverrides {

    private static final Map<String, byte[]> OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> ORIGINALS = new ConcurrentHashMap<>();

    private ClassOverrides() {
    }

    static byte[] lookup(String internalName) {
        return OVERRIDES.get(internalName);
    }

    /** Records the bytes seen at first load, so a class can be restored exactly. */
    static void rememberOriginal(String internalName, byte[] bytes) {
        ORIGINALS.putIfAbsent(internalName, bytes);
    }

    /**
     * Installs replacement bytecode and makes it take effect immediately if the class is
     * already loaded. A class that is not yet loaded is picked up by the load-time
     * transformer instead, so both cases are covered by one call.
     */
    public static void install(String binaryName, byte[] bytes) {
        String internal = binaryName.replace('.', '/');
        OVERRIDES.put(internal, bytes);
        retransformIfLoaded(binaryName);
    }

    /** Removes an override, restoring the class as it was originally loaded. */
    public static void remove(String binaryName) {
        String internal = binaryName.replace('.', '/');
        if (OVERRIDES.remove(internal) != null) {
            retransformIfLoaded(binaryName);
        }
    }

    public static void removeAll() {
        for (String internal : OVERRIDES.keySet().toArray(new String[0])) {
            remove(internal.replace('/', '.'));
        }
    }

    private static void retransformIfLoaded(String binaryName) {
        for (Class<?> c : JzapAgent.instrumentation().getAllLoadedClasses()) {
            if (binaryName.equals(c.getName())) {
                try {
                    JzapAgent.instrumentation().retransformClasses(c);
                } catch (Exception e) {
                    throw new IllegalStateException("cannot retransform " + binaryName + ": " + e, e);
                }
                return;
            }
        }
    }

    /** Bytes as first seen for a class, or null if it has not been loaded. */
    public static byte[] original(String binaryName) {
        return ORIGINALS.get(binaryName.replace('.', '/'));
    }
}
