package io.github.huyz0.jzap.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIoTest {

    private static final String MINIMAL = """
            {
              "schemaVersion": 1,
              "modules": [
                {
                  "id": ":app",
                  "mutableCodePaths": ["build/classes/java/main"],
                  "sourceRoots": ["src/main/java"],
                  "testClassPaths": ["build/classes/java/test"],
                  "testClasspath": ["build/classes/java/test", "libs/junit.jar"]
                }
              ]
            }
            """;

    @Test
    void readsMinimalModelAndAppliesDefaults(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("model.json");
        Files.writeString(f, MINIMAL);

        ProjectModel m = new ModelIo().readProjectModel(f);

        assertEquals(1, m.modules().size());
        assertEquals(":app", m.modules().get(0).id());
        assertEquals(ScopeKind.ALL, m.scope().kind());
        assertEquals("line", m.scope().granularity());
        assertEquals(List.of("console", "json"), m.reporters());
        assertEquals(1000, m.maxMutantsPerMinion());
        assertEquals(1, m.threads(),
                "the default is one analysis JVM, not one per core: guessing measured 31% slower "
                + "than a single thread on a four-core CI runner");
    }

    @Test
    void roundTripsToIdenticalBytes(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("model.json");
        Files.writeString(f, MINIMAL);
        ModelIo io = new ModelIo();

        ProjectModel first = io.readProjectModel(f);
        Path out = dir.resolve("out.json");
        io.write(out, first);
        ProjectModel second = io.readProjectModel(out);
        Path out2 = dir.resolve("out2.json");
        io.write(out2, second);

        assertEquals(first, second);
        assertEquals(Files.readString(out), Files.readString(out2));
    }

    @Test
    void unknownFieldIsWarnedNotFatal(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("model.json");
        Files.writeString(f, MINIMAL.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"turbo\": true,"));
        ModelIo io = new ModelIo();

        io.readProjectModel(f);

        assertTrue(io.warnings().stream().anyMatch(w -> w.contains("unknown field 'turbo'")),
                () -> "expected a warning about 'turbo', got " + io.warnings());
    }

    @Test
    void unknownSchemaVersionIsFatalAndSaysWhatToDo(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("model.json");
        Files.writeString(f, MINIMAL.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"));

        ModelValidationException e = assertThrows(ModelValidationException.class,
                () -> new ModelIo().readProjectModel(f));

        assertTrue(e.getMessage().contains("99"), e.getMessage());
        assertTrue(e.getMessage().contains("understands version 1"), e.getMessage());
    }

    @Test
    void missingModulesIsFatal(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("model.json");
        Files.writeString(f, "{\"schemaVersion\": 1, \"modules\": []}");

        ModelValidationException e = assertThrows(ModelValidationException.class,
                () -> new ModelIo().readProjectModel(f));
        assertTrue(e.getMessage().contains("at least one module"), e.getMessage());
    }

    @Test
    void mutantKeyRoundTrips() {
        MutantKey k = new MutantKey("com.example.Foo", "compute", "(II)I", 42, "MATH", 1);
        assertEquals(k, MutantKey.parse(k.asString()));
        assertEquals("com.example.Foo::compute(II)I::42::MATH#1", k.asString());
    }

    @Test
    void mutantKeyParsesConstructors() {
        MutantKey k = new MutantKey("com.example.Foo", "<init>", "()V", 7, "NEGATE_CONDITIONALS", 0);
        assertEquals(k, MutantKey.parse(k.asString()));
    }

    // ------------------------------------------------------------ malformed input

    @Test
    void aTopLevelArrayIsRejectedWithItsPath(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("model.json"), "[]");

        ModelValidationException e = assertThrows(ModelValidationException.class,
                () -> new ModelIo().readProjectModel(file));
        assertTrue(e.getMessage().contains("top level must be a JSON object"), e.getMessage());
        assertTrue(e.getMessage().contains("model.json"),
                "the file has to be named, since a build may write several: " + e.getMessage());
    }

    @Test
    void aMissingSchemaVersionIsAssumedWithAWarning(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("model.json"), """
                { "modules": [ { "id": ":a" } ] }
                """);

        ModelIo io = new ModelIo();
        io.readProjectModel(file);

        assertTrue(io.warnings().stream().anyMatch(w -> w.contains("no schemaVersion, assuming")),
                "an adapter that predates the field should still work, loudly: " + io.warnings());
    }

    @Test
    void aSchemaVersionThatIsNotAnIntegerIsRejected(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("model.json"), """
                { "schemaVersion": "one", "modules": [ { "id": ":a" } ] }
                """);

        ModelValidationException e = assertThrows(ModelValidationException.class,
                () -> new ModelIo().readProjectModel(file));
        assertTrue(e.getMessage().contains("schemaVersion must be an integer"), e.getMessage());
    }

    @Test
    void aFieldOfTheWrongTypeIsReportedWithTheUnderlyingDetail(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("model.json"), """
                { "schemaVersion": 1, "threads": "many", "modules": [ { "id": ":a" } ] }
                """);

        ModelValidationException e = assertThrows(ModelValidationException.class,
                () -> new ModelIo().readProjectModel(file));
        assertTrue(e.getMessage().contains("model.json"), e.getMessage());
    }

    @Test
    void aFileThatDoesNotExistFailsAsAnIoProblem(@TempDir Path dir) {
        assertThrows(UncheckedIOException.class,
                () -> new ModelIo().readProjectModel(dir.resolve("absent.json")));
    }

    @Test
    void anUnknownFieldInsideAModuleIsWarnedAboutByPosition(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("model.json"), """
                {
                  "schemaVersion": 1,
                  "modules": [
                    { "id": ":a" },
                    { "id": ":b", "turbo": true }
                  ]
                }
                """);

        ModelIo io = new ModelIo();
        io.readProjectModel(file);

        assertTrue(io.warnings().stream().anyMatch(w -> w.contains("modules[1].turbo")),
                "naming which module makes a typo findable in a reactor: " + io.warnings());
    }

    @Test
    void anUnknownFieldInsideTheScopeIsWarnedAbout(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("model.json"), """
                {
                  "schemaVersion": 1,
                  "modules": [ { "id": ":a" } ],
                  "scope": { "kind": "ALL", "granularrity": "line" }
                }
                """);

        ModelIo io = new ModelIo();
        io.readProjectModel(file);

        assertTrue(io.warnings().stream().anyMatch(w -> w.contains("scope.granularrity")),
                "the likeliest mistake in the scope is a misspelling: " + io.warnings());
    }

    @Test
    void warningsFromAPreviousReadDoNotLeakIntoTheNext(@TempDir Path dir) throws Exception {
        Path noisy = Files.writeString(dir.resolve("noisy.json"), """
                { "schemaVersion": 1, "modules": [ { "id": ":a" } ], "turbo": true }
                """);
        Path clean = Files.writeString(dir.resolve("clean.json"), """
                { "schemaVersion": 1, "modules": [ { "id": ":a" } ] }
                """);

        ModelIo io = new ModelIo();
        io.readProjectModel(noisy);
        assertFalse(io.warnings().isEmpty());

        io.readProjectModel(clean);
        assertTrue(io.warnings().isEmpty(),
                "the CLI prints these, so a stale warning would point at the wrong file");
    }

    // ------------------------------------------------------------ writing

    @Test
    void aModelRoundTripsThroughItsOwnWriter(@TempDir Path dir) {
        ProjectModel original = new ProjectModel(1,
                List.of(new ModuleModel(":app", List.of("classes"), List.of("src"),
                        List.of("test-classes"), List.of("junit.jar"), null,
                        List.of("-Xmx1g"), null)),
                Scope.diff("HEAD~1", Scope.LOCAL), null, List.of("json", "html"),
                4, 2.0, 5000, 500, "schemata");
        ModelIo io = new ModelIo();
        Path file = dir.resolve("nested/model.json");

        io.write(file, original);
        ProjectModel read = io.readProjectModel(file);

        assertEquals(original.modules(), read.modules());
        assertEquals(original.scope(), read.scope());
        assertEquals(original.reporters(), read.reporters());
        assertEquals(original.threads(), read.threads());
        assertEquals(original.timeoutFactor(), read.timeoutFactor());
        assertEquals(original.maxMutantsPerMinion(), read.maxMutantsPerMinion());
        assertEquals(original.engine(), read.engine());
        assertTrue(io.warnings().isEmpty(),
                "what jzap writes must not warn when jzap reads it back -- a derived getter "
                        + "serialised as a field is exactly how that happens: " + io.warnings());
    }

    @Test
    void writingCreatesTheDirectoryItNeeds(@TempDir Path dir) {
        Path file = dir.resolve("a/b/c/model.json");

        new ModelIo().write(file, new ProjectModel(1,
                List.of(new ModuleModel(":a", List.of("classes"), List.of(), List.of(),
                        List.of(), null, List.of(), null)),
                Scope.all(), null, List.of("json"), 1, 1.5, 4000, 1000, null));

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void writeStringProducesSomethingReadable() {
        String json = new ModelIo().writeString(Scope.all());

        assertTrue(json.contains("\"kind\""), json);
        assertTrue(json.contains("ALL"), json);
    }
}
