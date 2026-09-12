package io.github.huyz0.jzap.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assigns a probe id to each (class, line) pair.
 *
 * <p>Ids are allocated in the order classes are instrumented, and {@link ClassScanner}
 * returns classes in sorted order, so the mapping is reproducible across runs.
 */
public final class ProbeIndex {

    private final Map<String, Integer> ids = new LinkedHashMap<>();

    private static String key(String className, int line) {
        return className + ':' + line;
    }

    int allocate(String className, int line) {
        return ids.computeIfAbsent(key(className, line), k -> ids.size());
    }

    /** Probe id for a line, or -1 if that line was never instrumented. */
    public int lookup(String className, int line) {
        return ids.getOrDefault(key(className, line), -1);
    }

    public int size() {
        return ids.size();
    }
}
