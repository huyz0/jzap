package io.github.huyz0.jzap.e2e;

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
 * Reads the descriptor the fixture's own Gradle build publishes, and turns it into a project
 * model. This is a stand-in for the Gradle adapter of M18: the same information, computed by
 * the build that owns it, consumed by the engine through the one seam.
 */
final class Fixture {

    private final Properties properties = new Properties();

    Fixture() {
        this("jzap.fixture.descriptor", ":fixtures:sample-java");
    }

    static Fixture hang() {
        return new Fixture("jzap.hang.descriptor", ":fixtures:hang-java");
    }

    static Fixture kotlin() {
        return new Fixture("jzap.kotlin.descriptor", ":fixtures:kotlin-sample");
    }

    static Fixture kotest() {
        return new Fixture("jzap.kotest.descriptor", ":fixtures:kotest-sample");
    }

    /**
     * A two-module project: a library with no tests of its own, and an application module whose
     * tests exercise it.
     */
    static ProjectModel multiModule() {
        Fixture core = new Fixture("jzap.multi.core.descriptor", ":fixtures:multi-core");
        Fixture app = new Fixture("jzap.multi.app.descriptor", ":fixtures:multi-app");
        return new ProjectModel(1,
                List.of(core.module(), app.module()),
                Scope.all(), null, List.of("json"), 1, 1.5, 4000, 100, null);
    }

    ModuleModel module() {
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

    Path projectRoot() {
        return Path.of(properties.getProperty("projectRoot"));
    }

    /**
     * The 1-based line number of the first source line containing {@code needle}.
     *
     * <p>Tests derive line numbers this way rather than hard-coding them, because a fixture's
     * comments get edited and a test that then fails is reporting on the comment, not the code.
     */
    int lineContaining(String relativePath, String needle) {
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
    int declaredTestCount(Path testSourceFile) {
        try {
            return (int) Files.readAllLines(testSourceFile).stream()
                    .filter(line -> line.trim().equals("@Test"))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + testSourceFile, e);
        }
    }

    ProjectModel model(Scope scope) {
        return new ProjectModel(1, List.of(module()), scope, null, List.of("json"), 1, 1.5, 4000, 100, null);
    }
}
