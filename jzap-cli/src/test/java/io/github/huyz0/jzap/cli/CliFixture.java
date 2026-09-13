package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.model.ModuleModel;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * The sample fixture, as a project model file on disk.
 *
 * <p>The CLI's whole input is that file, so a test that wants to drive the CLI has to produce a
 * real one. The fixture's own Gradle build publishes the paths; this turns them into the JSON a
 * build-tool adapter would have written.
 */
final class CliFixture {

    private final Properties properties = new Properties();

    CliFixture() {
        String descriptor = System.getProperty("jzap.fixture.descriptor");
        if (descriptor == null) {
            throw new IllegalStateException(
                    "jzap.fixture.descriptor is not set; run this through Gradle");
        }
        try (var in = Files.newInputStream(Path.of(descriptor))) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture descriptor " + descriptor, e);
        }
    }

    private ModuleModel module() {
        return new ModuleModel(
                ":fixtures:sample-java",
                paths("mainClasses"),
                paths("sourceRoot"),
                paths("testClasses"),
                paths("testRuntimeClasspath"),
                null,
                List.of("-Xmx512m"),
                null);
    }

    /** Writes the model as JSON, the way an adapter would, and returns the file. */
    Path writeModel(Path dir) {
        return writeModelWith(dir, "");
    }

    /**
     * @param extraScopeFields raw JSON to splice into the scope object, for a scope the CLI
     *                         cannot be asked for on the command line
     */
    Path writeModelWith(Path dir, String extraScopeFields) {
        ModuleModel module = module();
        String json = """
                {
                  "schemaVersion": 1,
                  "threads": 1,
                  "reporters": ["json"],
                  "scope": { "kind": "ALL"%s },
                  "modules": [
                    {
                      "id": "%s",
                      "mutableCodePaths": [%s],
                      "sourceRoots": [%s],
                      "testClassPaths": [%s],
                      "testClasspath": [%s],
                      "jvmArgs": ["-Xmx512m"]
                    }
                  ]
                }
                """.formatted(
                extraScopeFields,
                module.id(),
                quote(module.mutableCodePaths()),
                quote(module.sourceRoots()),
                quote(module.testClassPaths()),
                quote(module.testClasspath()));
        return write(dir.resolve("jzap-model.json"), json);
    }

    static Path write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static String quote(List<String> paths) {
        return paths.stream()
                .map(p -> "\"" + p.replace("\\", "\\\\") + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private List<String> paths(String key) {
        String value = properties.getProperty(key, "");
        return value.isEmpty() ? List.of() : List.of(value.split(File.pathSeparator));
    }
}
