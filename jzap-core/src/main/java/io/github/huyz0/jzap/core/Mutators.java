package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.ConditionalsBoundaryMutator;
import io.github.huyz0.jzap.core.mutator.EmptyReturnsMutator;
import io.github.huyz0.jzap.core.mutator.FalseReturnsMutator;
import io.github.huyz0.jzap.core.mutator.IncrementsMutator;
import io.github.huyz0.jzap.core.mutator.InvertNegsMutator;
import io.github.huyz0.jzap.core.mutator.MathMutator;
import io.github.huyz0.jzap.core.mutator.NegateConditionalsMutator;
import io.github.huyz0.jzap.core.mutator.PrimitiveReturnsMutator;
import io.github.huyz0.jzap.core.mutator.TrueReturnsMutator;
import io.github.huyz0.jzap.core.mutator.VoidMethodCallsMutator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutator registry.
 *
 * <p>The default set is deliberately the same ten mutators as PIT's DEFAULTS, with the same
 * ids. That is what makes the parity harness able to compare inventories mutant for mutant
 * instead of guessing at correspondences.
 */
public final class Mutators {

    private static final Map<String, Mutator> ALL = new LinkedHashMap<>();

    static {
        register(new ConditionalsBoundaryMutator());
        register(new IncrementsMutator());
        register(new InvertNegsMutator());
        register(new MathMutator());
        register(new NegateConditionalsMutator());
        register(new VoidMethodCallsMutator());
        register(new EmptyReturnsMutator());
        register(new FalseReturnsMutator());
        register(new TrueReturnsMutator());
        register(new PrimitiveReturnsMutator());
    }

    private Mutators() {
    }

    private static void register(Mutator m) {
        ALL.put(m.id(), m);
    }

    public static List<Mutator> defaults() {
        return List.copyOf(ALL.values());
    }

    public static List<String> defaultIds() {
        return List.copyOf(ALL.keySet());
    }

    public static Mutator byId(String id) {
        Mutator m = ALL.get(id);
        if (m == null) {
            throw new IllegalArgumentException("unknown mutator '" + id + "'. Known mutators: "
                    + String.join(", ", ALL.keySet()));
        }
        return m;
    }

    /** Resolves a requested set, falling back to the defaults when none is requested. */
    public static List<Mutator> resolve(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return defaults();
        }
        return ids.stream().map(Mutators::byId).toList();
    }
}
