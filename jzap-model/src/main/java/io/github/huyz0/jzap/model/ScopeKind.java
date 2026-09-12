package io.github.huyz0.jzap.model;

public enum ScopeKind {
    /** Every mutant in every target class. */
    ALL,
    /** Mutants on lines changed between two git refs. */
    DIFF,
    /** Mutants on lines changed by a unified diff file, with no git repository required. */
    PATCH
}
