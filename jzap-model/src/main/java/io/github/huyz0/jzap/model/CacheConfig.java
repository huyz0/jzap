package io.github.huyz0.jzap.model;

/**
 * @param dir       directory holding cached verdicts
 * @param enabled   whether to read and write the cache
 * @param toolchain fingerprint of the JDK that produced the bytecode. A cache recorded under
 *                  one toolchain refuses to be used under another, because bytecode from
 *                  different javac versions and platforms differs; returning a wrong verdict
 *                  would be worse than recomputing.
 */
public record CacheConfig(String dir, boolean enabled, String toolchain) {

    public static CacheConfig disabled() {
        return new CacheConfig(null, false, null);
    }

    public static CacheConfig at(String dir) {
        return new CacheConfig(dir, true, null);
    }
}
