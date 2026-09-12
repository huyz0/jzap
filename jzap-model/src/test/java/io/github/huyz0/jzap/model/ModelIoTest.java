package io.github.huyz0.jzap.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(100, m.maxMutantsPerMinion());
        assertTrue(m.threads() >= 1);
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
}
