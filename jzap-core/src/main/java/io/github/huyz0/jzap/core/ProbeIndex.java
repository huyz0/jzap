package io.github.huyz0.jzap.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assigns a probe id to each (class, method, line) triple.
 *
 * <p>Method-scoped rather than class-scoped because one source line can compile into several
 * methods. Kotlin's inline functions make this unavoidable: the body of an inline function exists
 * once at its declaration and once inside every caller, all reporting the same source line after
 * translation. Keyed by line alone they share a probe, and a test that only exercises a call site
 * makes the unreachable declaration look covered. Lambdas in Java raise the same question more
 * quietly.
 *
 * <p>Ids are allocated in the order classes are instrumented, and {@link ClassScanner} returns
 * classes in sorted order, so the mapping is reproducible across runs.
 */
final class ProbeIndex {

    private final Map<String, Integer> ids = new LinkedHashMap<>();
    private final Map<Integer, String> locations = new LinkedHashMap<>();

    /** The location key a mutant and its probe must agree on. */
    public static String key(String className, String methodName, String descriptor, int line) {
        return className + '#' + methodName + descriptor + ':' + line;
    }

    int allocate(String className, String methodName, String descriptor, int line) {
        return ids.computeIfAbsent(key(className, methodName, descriptor, line), k -> {
            int id = ids.size();
            locations.put(id, k);
            return id;
        });
    }

    /** The {@code class:line} a probe id stands for, or null if it was never allocated. */
    public String locationOf(int probeId) {
        return locations.get(probeId);
    }

    /** Probe id for a location, or -1 if it was never instrumented. */
    public int lookup(String className, String methodName, String descriptor, int line) {
        return ids.getOrDefault(key(className, methodName, descriptor, line), -1);
    }

    public int size() {
        return ids.size();
    }
}
