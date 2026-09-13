package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import io.github.huyz0.jzap.model.ProjectModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Per-test coverage plus the baselines that timeouts and test ordering are derived from.
 *
 * @param testsByLocation {@code class#methoddescriptor:line} to the tests that execute it
 * @param durations       test id to its unmutated duration, the wall-clock backstop's input
 * @param loopIterations  test id to the back edges it executed unmutated, which the runaway-loop
 *                        limit is derived from
 * @param failingTests    tests that failed with no mutant applied, excluded from selection
 * @param testIds         every test the coverage phase ran, in the order it ran them
 * @param testModules     test id to the module whose classpath can run it
 * @param testClassHashes test class binary name to bytecode hash, for cache validity
 * @param previousKillingTest what killed a mutant last time, for ordering
 */
record Coverage(
        Map<String, Set<String>> testsByLocation,
        Map<String, Long> durations,
        Map<String, Long> loopIterations,
        Set<String> failingTests,
        List<String> testIds,
        Map<String, String> testModules,
        Map<String, String> testClassHashes,
        Function<MutantKey, Optional<String>> previousKillingTest) {

    /**
     * Tests that execute the mutated line: the one that killed it last time first, then the
     * rest cheapest first.
     *
     * <p>Ordering matters because of early exit. Everything tried before the test that
     * actually kills a mutant is wasted, and the test that killed it in the previous run is
     * overwhelmingly likely to kill it again.
     */
    List<String> selectFor(Mutant mutant) {
        Set<String> tests = testsByLocation.get(ProbeIndex.key(
                mutant.key().className(), mutant.key().methodName(),
                mutant.key().descriptor(), mutant.key().line()));
        if (tests == null) {
            return List.of();
        }
        String killedItLastTime = previousKillingTest.apply(mutant.key()).orElse(null);
        return tests.stream()
                .filter(t -> !failingTests.contains(t))
                .sorted(Comparator
                        .comparing((String t) -> !t.equals(killedItLastTime))
                        .thenComparingLong(t -> durations.getOrDefault(t, 0L)))
                .toList();
    }

    /** The selected tests grouped by the module that has to run them, order preserved. */
    Map<String, List<String>> groupByModule(List<String> selected) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String test : selected) {
            String module = testModules.get(test);
            if (module != null) {
                grouped.computeIfAbsent(module, k -> new ArrayList<>()).add(test);
            }
        }
        return grouped;
    }

    /**
     * Loop iterations past which a mutant is declared runaway.
     *
     * <p>Ten times what the unmutated code needed, with a floor so that code which loops
     * barely at all still has room. A mutant that does an order of magnitude more work than
     * the original is not doing the same job slowly; it is not stopping.
     */
    long iterationLimitFor(List<String> selected) {
        long baseline = selected.stream()
                .mapToLong(t -> loopIterations.getOrDefault(t, 0L))
                .max().orElse(0L);
        return Math.max(1_000_000L, baseline * 10);
    }

    int timeoutFor(List<String> selected, ProjectModel model) {
        long baseline = selected.stream().mapToLong(t -> durations.getOrDefault(t, 0L)).sum();
        long timeout = (long) (baseline * model.timeoutFactor()) + model.timeoutConstMillis();
        return (int) Math.max(1_000L, Math.min(timeout, 600_000L));
    }
}
