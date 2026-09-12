package io.github.huyz0.jzap.core;

import org.objectweb.asm.MethodVisitor;

/**
 * A single kind of code change.
 *
 * <p>Ids match PIT's mutator names wherever the semantics match, because the parity harness
 * compares mutant inventories and a rename would turn an equivalent mutant into an
 * unexplained difference. See docs/parity-and-benchmarks.md and tools/parity/mutator-mapping.yaml.
 */
public interface Mutator {

    /** Stable id, e.g. {@code MATH}. Appears in mutant keys, reports and the cache. */
    String id();

    /** Wraps the downstream visitor, registering and optionally applying mutations. */
    MethodVisitor visit(MutationContext ctx, MethodVisitor next);
}
