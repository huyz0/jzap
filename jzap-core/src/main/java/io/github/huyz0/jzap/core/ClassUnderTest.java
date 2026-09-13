package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.ModuleModel;

/**
 * A compiled class in scope for mutation, and the module whose output it came from.
 *
 * <p>The module travels with the class because a mutant's verdict depends on it: the module's
 * classpath is what an analysis JVM has to be started with to load the class at all.
 *
 * @param hash bytecode hash, which is what the cache keys a verdict's validity on
 */
record ClassUnderTest(String binaryName, byte[] bytes, String hash, ModuleModel module) {
}
