package io.github.huyz0.jzap.core.testing;

import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.model.ProjectModel;
import io.github.huyz0.jzap.model.Scope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Reads the descriptor a fixture project's own Gradle build publishes, and turns it into a
 * project model.
 *
 * <p>A stand-in for the Gradle adapter: the same information, computed by the build that owns it,
 * consumed by the engine through the one seam. Shared as a test fixture because both this
 * module's tests and the end-to-end ones analyse the same fixture projects, and a second copy of
 * this would drift from the descriptors the fixture builds write.
 */
public final class Fixture {

    private final Properties properties = new Properties();

    public Fixture() {
        this("jzap.fixture.descriptor", ":fixtures:sample-java");
    }

    public static Fixture hang() {
        return new Fixture("jzap.hang.descriptor", ":fixtures:hang-java");
    }

    public static Fixture kotlin() {
        return new Fixture("jzap.kotlin.descriptor", ":fixtures:kotlin-sample");
    }

    /** A fixture whose suite is already failing before any mutant is applied. */
    public static Fixture red() {
        return new Fixture("jzap.red.descriptor", ":fixtures:red-java");
    }

    /** A fixture whose production code keeps static state across calls. */
    public static Fixture stateful() {
        return new Fixture("jzap.stateful.descriptor", ":fixtures:stateful-java");
    }

    /**
     * A fixture slow enough to justify more than one analysis JVM.
     *
     * <p>Two classes, four tests each, every test sleeping 100ms. The engine sizes its worker
     * pool from measured test durations, so every other fixture here collapses to one worker and
     * leaves the concurrent path unexercised.
     */
    public static Fixture parallel() {
        return new Fixture("jzap.parallel.descriptor", ":fixtures:parallel-java");
    }

    /**
     * A fixture whose mutant blocks rather than loops.
     *
     * <p>The loop guard counts back edges, so blocking trips nothing: this is the one case the
     * wall-clock backstop has to catch on its own.
     */
    public static Fixture blocking() {
        return new Fixture("jzap.blocking.descriptor", ":fixtures:blocking-java");
    }

    /**
     * Two classes, where one class's tests also execute the other's code.
     *
     * <p>The combination that catches mutants leaking across classes. A worker analyses a whole
     * class at a time and installs one schemata class per class; the mutant it selects is a single
     * global index, so a schemata class left installed from an earlier class answers to the index
     * being selected for the current one. Every other fixture here has either one class with
     * coverage or two that never call each other, which is why this needs its own.
     */
    public static Fixture crosstalk() {
        return new Fixture("jzap.crosstalk.descriptor", ":fixtures:crosstalk-java");
    }

    public static Fixture kotest() {
        return new Fixture("jzap.kotest.descriptor", ":fixtures:kotest-sample");
    }

    /**
     * A two-module project: a library with no tests of its own, and an application module whose
     * tests exercise it.
     */
    public static ProjectModel multiModule() {
        Fixture core = new Fixture("jzap.multi.core.descriptor", ":fixtures:multi-core");
        Fixture app = new Fixture("jzap.multi.app.descriptor", ":fixtures:multi-app");
        return new ProjectModel(1,
                List.of(core.module(), app.module()),
                Scope.all(), null, List.of("json"), 1, 1.5, 4000, 100, null);
    }

    public ModuleModel module() {
        return new ModuleModel(
                moduleId,
                paths("mainClasses"),
                paths("sourceRoot"),
                paths("testClasses"),
                paths("testRuntimeClasspath"),
                null,
                List.of("-Xmx512m"),
                null);
    }

    private final String moduleId;

    private Fixture(String property, String moduleId) {
        this.moduleId = moduleId;
        String descriptor = System.getProperty(property);
        if (descriptor == null) {
            throw new IllegalStateException(property + " is not set; run this through Gradle");
        }
        try (var in = Files.newInputStream(Path.of(descriptor))) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture descriptor " + descriptor, e);
        }
    }

    private List<String> paths(String key) {
        String value = properties.getProperty(key, "");
        return value.isEmpty() ? List.of() : List.of(value.split(java.io.File.pathSeparator));
    }

    public Path projectRoot() {
        return Path.of(properties.getProperty("projectRoot"));
    }

    /**
     * The 1-based line number of the first source line containing {@code needle}.
     *
     * <p>Tests derive line numbers this way rather than hard-coding them, because a fixture's
     * comments get edited and a test that then fails is reporting on the comment, not the code.
     */
    public int lineContaining(String relativePath, String needle) {
        Path file = Path.of(properties.getProperty("sourceRoot")).resolve(relativePath);
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    return i + 1;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture source " + file, e);
        }
        throw new IllegalArgumentException("no line containing '" + needle + "' in " + file);
    }

    /** Number of test methods declared in the fixture's test sources. */
    public int declaredTestCount(Path testSourceFile) {
        try {
            return (int) Files.readAllLines(testSourceFile).stream()
                    .filter(line -> line.trim().equals("@Test"))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + testSourceFile, e);
        }
    }

    public ProjectModel model(Scope scope) {
        return new ProjectModel(1, List.of(module()), scope, null, List.of("json"), 1, 1.5, 4000,
                100, null);
    }

    /**
     * @param maxMutantsPerMinion 1 gives every mutant a JVM of its own, which is the sound but
     *                            slow way and therefore the reference for whether reuse is safe
     */
    public ProjectModel model(Scope scope, int maxMutantsPerMinion) {
        return new ProjectModel(1, List.of(module()), scope, null, List.of("json"), 1, 1.5, 4000,
                maxMutantsPerMinion, null);
    }
}
