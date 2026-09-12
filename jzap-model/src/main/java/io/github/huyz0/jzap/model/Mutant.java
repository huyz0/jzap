package io.github.huyz0.jzap.model;

import java.util.List;

/**
 * A discovered mutant and, once analysed, its outcome.
 *
 * @param key            tool-independent identity
 * @param moduleId       module the mutated class belongs to
 * @param sourceFile     source file path relative to a source root, e.g. {@code com/example/Foo.java}
 * @param description    human-readable description of the change, source-faithful where possible
 * @param status         outcome, or null before analysis
 * @param killingTest    unique id of the test that killed it, when known
 * @param coveringTests  number of tests that execute the mutated line
 * @param testsRun       number of tests actually executed against this mutant
 * @param durationMillis time spent analysing this mutant
 */
public record Mutant(
        MutantKey key,
        String moduleId,
        String sourceFile,
        String description,
        MutantStatus status,
        String killingTest,
        int coveringTests,
        int testsRun,
        long durationMillis) {

    public static Mutant discovered(MutantKey key, String moduleId, String sourceFile, String description) {
        return new Mutant(key, moduleId, sourceFile, description, null, null, 0, 0, 0L);
    }

    public Mutant withOutcome(MutantStatus status, String killingTest, int coveringTests, int testsRun, long durationMillis) {
        return new Mutant(key, moduleId, sourceFile, description, status, killingTest, coveringTests, testsRun, durationMillis);
    }

    public Mutant withCoveringTests(int coveringTests) {
        return new Mutant(key, moduleId, sourceFile, description, status, killingTest, coveringTests, testsRun, durationMillis);
    }

    public static List<Mutant> sorted(List<Mutant> mutants) {
        return mutants.stream().sorted((a, b) -> a.key().compareTo(b.key())).toList();
    }
}
