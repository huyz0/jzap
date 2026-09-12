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

    ProjectModel model(Scope scope) {
        ModuleModel module = new ModuleModel(
                moduleId,
                paths("mainClasses"),
                paths("sourceRoot"),
                paths("testClasses"),
                paths("testRuntimeClasspath"),
                null,
                List.of("-Xmx512m"),
                null);
        return new ProjectModel(1, List.of(module), scope, null, List.of("json"), 1, 1.5, 4000, 100);
    }
}
