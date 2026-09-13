package io.github.huyz0.jzap.core.mutator;

import io.github.huyz0.jzap.core.Mutator;

import java.util.List;

/** The extreme-mutation operators, exposed for registration. */
public final class ExtremeMutators {

    private ExtremeMutators() {
    }

    public static List<Mutator> all() {
        return List.of(new ExtremeMutator.VoidBody(), new ExtremeMutator.ConstantReturn());
    }
}
