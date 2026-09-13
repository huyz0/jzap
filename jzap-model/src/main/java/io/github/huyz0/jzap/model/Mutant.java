package io.github.huyz0.jzap.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

    /**
     * Where this mutant's source sits relative to a source root, e.g. {@code ex/Calc.java}.
     *
     * <p>Derived rather than stored, because the class file records only a bare file name and the
     * package has to come from the class name. Lives here because two independent things have to
     * agree on the answer: diff scoping matches this against the paths in a patch or a git range,
     * and every reporter groups and links by it. Two copies of the rule would mean a run could
     * analyse one path and report another, with nothing to say which was right.
     *
     * <p>Ignored by the serialiser: a derived value written into a report would come back as an
     * unknown property when that report is read.
     */
    @JsonIgnore
    public String sourcePath() {
        String className = key.className();
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot < 0
                ? ""
                : className.substring(0, lastDot).replace('.', '/') + "/";
        String file = sourceFile;
        if (file == null || file.isBlank()) {
            // No SourceFile attribute: fall back to the outermost class's name, since a nested or
            // anonymous class lives in the file its outer class is named after.
            String simple = lastDot < 0 ? className : className.substring(lastDot + 1);
            int dollar = simple.indexOf('$');
            file = (dollar < 0 ? simple : simple.substring(0, dollar)) + ".java";
        }
        return packagePath + file;
    }

    public static List<Mutant> sorted(List<Mutant> mutants) {
        return mutants.stream().sorted((a, b) -> a.key().compareTo(b.key())).toList();
    }
}
