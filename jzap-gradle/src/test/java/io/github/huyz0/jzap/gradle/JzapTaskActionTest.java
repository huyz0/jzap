package io.github.huyz0.jzap.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task action itself: the model it writes, and what it does when it cannot run.
 *
 * <p>The model file is the entire contract between this plugin and the engine, so it is worth
 * asserting on directly rather than only through a build that happens to succeed. A scope field
 * spelled wrongly here would not fail the build; it would quietly analyse the wrong thing.
 */
class JzapTaskActionTest {

    private static JzapTask taskIn(Path dir) {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply("io.github.huyz0.jzap");
        return (JzapTask) project.getTasks().getByName(JzapPlugin.ANALYSE_TASK);
    }

    /** Runs the action far enough to write the model, then stops at the missing engine. */
    private static String modelWrittenBy(JzapTask task) throws Exception {
        Path reportDir = task.getReportDir().get().getAsFile().toPath();
        // No engine on the classpath, so the action fails -- after writing the model, which is
        // the part under test here. Running the engine is JzapPluginTest's job.
        assertThrows(GradleException.class, task::analyse);
        Path model = reportDir.resolve("jzap-model.json");
        assertTrue(Files.isRegularFile(model),
                "the model is written before the engine is invoked: " + model);
        return Files.readString(model, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ refusing to run

    @Test
    void withNoEngineItSaysBothWaysToSupplyOne(@TempDir Path dir) {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of());

        GradleException e = assertThrows(GradleException.class, task::analyse);

        assertTrue(e.getMessage().contains("engineVersion"),
                "one way is to name a published version: " + e.getMessage());
        assertTrue(e.getMessage().contains("engineClasspath"),
                "the other is to point at a local build: " + e.getMessage());
    }

    @Test
    void withNothingCompiledItSaysSoAndDoesNotFail(@TempDir Path dir) {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getMutableCodePaths().setFrom(List.of(new File(dir.toFile(), "never-compiled")));

        task.analyse();

        assertFalse(Files.exists(task.getReportDir().get().getAsFile().toPath()
                        .resolve("jzap-model.json")),
                "a module with no compiled output has nothing to analyse, and that is not an error");
    }

    // ------------------------------------------------------------ the model it writes

    @Test
    void theModelNamesTheModuleAndItsPaths(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        Path classes = Files.createDirectories(dir.resolve("build/classes/java/main"));
        task.getMutableCodePaths().setFrom(List.of(classes.toFile()));

        String model = modelWrittenBy(task);

        assertTrue(model.contains("\"schemaVersion\": 1"), model);
        assertTrue(model.contains("\"id\": \":test\"") || model.contains("\"id\": \":"), model);
        assertTrue(model.contains("classes/java/main") || model.contains("classes\\\\java"), model);
    }

    @Test
    void withoutRefsTheScopeIsEverything(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getMutableCodePaths().setFrom(
                List.of(Files.createDirectories(dir.resolve("classes")).toFile()));

        String model = modelWrittenBy(task);

        assertTrue(model.contains("\"kind\": \"ALL\""),
                "mutationTest is the whole-codebase run: " + model);
    }

    @Test
    void aRefMakesItADiffRun(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getMutableCodePaths().setFrom(
                List.of(Files.createDirectories(dir.resolve("classes")).toFile()));
        task.getFrom().set("origin/main");

        String model = modelWrittenBy(task);

        assertTrue(model.contains("\"kind\": \"DIFF\""),
                "naming a base ref is what selects diff scoping: " + model);
    }

    @Test
    void scopeSelectionsAndFiltersReachTheModel(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getMutableCodePaths().setFrom(
                List.of(Files.createDirectories(dir.resolve("classes")).toFile()));
        task.getScope().set("class");
        task.getIncludeClasses().set(List.of("app.*"));
        task.getExcludeClasses().set(List.of("app.generated.*"));
        task.getMutators().set(List.of("MATH", "INCREMENTS"));
        task.getMutateLoopCounters().set(true);
        task.getReporters().set(List.of("json", "html"));
        task.getThreads().set(4);

        String model = modelWrittenBy(task);

        assertTrue(model.contains("\"granularity\": \"class\""), model);
        assertTrue(model.contains("\"app.*\""), model);
        assertTrue(model.contains("\"app.generated.*\""), model);
        assertTrue(model.contains("\"MATH\", \"INCREMENTS\""), model);
        assertTrue(model.contains("\"threads\": 4"), model);
        assertTrue(model.contains("\"json\", \"html\""), model);
        assertTrue(model.contains("\"LOOP_COUNTER\""),
                "asking to mutate loop counters disables the filter by name: " + model);
    }

    @Test
    void loopCountersAreFilteredUnlessAskedFor(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getMutableCodePaths().setFrom(
                List.of(Files.createDirectories(dir.resolve("classes")).toFile()));

        String model = modelWrittenBy(task);

        assertTrue(model.contains("\"disabledFilters\": []"),
                "nothing is disabled by default, so the loop-counter filter stays on: " + model);
    }

    @Test
    void anAggregateRunCarriesEveryModulesFragment(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getModuleFragments().set(List.of(
                JzapTask.moduleFragment(":core", List.of(new File(dir.toFile(), "core/classes")),
                        List.of(), List.of(), List.of(), List.of()),
                JzapTask.moduleFragment(":app", List.of(new File(dir.toFile(), "app/classes")),
                        List.of(), List.of(), List.of(), List.of())));

        String model = modelWrittenBy(task);

        assertTrue(model.contains("\":core\""), model);
        assertTrue(model.contains("\":app\""), model);
        assertEquals(2, model.split("\"mutableCodePaths\"", -1).length - 1,
                "one entry per module, which is what lets a test in one kill a mutant in another");
    }

    @Test
    void theModelIsValidJson(@TempDir Path dir) throws Exception {
        JzapTask task = taskIn(dir);
        task.getEngineClasspath().setFrom(List.of(new File(dir.toFile(), "fake-engine.jar")));
        task.getMutableCodePaths().setFrom(
                List.of(Files.createDirectories(dir.resolve("classes")).toFile()));
        task.getJvmArgs().set(List.of("-Xmx1g"));

        String model = modelWrittenBy(task);

        assertEquals(count(model, '{'), count(model, '}'), model);
        assertEquals(count(model, '['), count(model, ']'), model);
        assertFalse(model.contains(",,"), "an empty list must not leave a stray comma: " + model);
        assertFalse(model.contains("[,") || model.contains(",]"), model);
    }

    private static long count(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
