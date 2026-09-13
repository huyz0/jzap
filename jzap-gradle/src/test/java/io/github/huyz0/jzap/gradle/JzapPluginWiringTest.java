package io.github.huyz0.jzap.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the plugin wires up, checked in this JVM.
 *
 * <p>{@link JzapPluginTest} runs real builds through TestKit, which is the only way to prove the
 * tasks work end to end -- but TestKit forks a Gradle daemon, so nothing it exercises can be
 * observed or measured from here. These tests apply the plugin in-process instead and assert the
 * wiring: which tasks exist, what the conventions are, and that each task is pointed at the
 * source sets rather than at guesses.
 *
 * <p>The two are complements. A convention that silently changed would pass TestKit's assertions
 * about a successful build while changing what every user's build actually analyses.
 */
class JzapPluginWiringTest {

    private static Project projectWithPlugin(Path dir) {
        Project project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        project.getPluginManager().apply("io.github.huyz0.jzap");
        return project;
    }

    @Test
    void appliesTheJavaPluginSoTheSourceSetsExist(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);

        assertTrue(project.getPluginManager().hasPlugin("java"),
                "the plugin reads source sets, so it has to ensure they are there");
    }

    @Test
    void registersTheThreeTasks(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);

        assertNotNull(project.getTasks().findByName(JzapPlugin.ANALYSE_TASK));
        assertNotNull(project.getTasks().findByName(JzapPlugin.DIFF_TASK));
        assertNotNull(project.getTasks().findByName(JzapPlugin.AGGREGATE_TASK),
                "a single-project build is its own root, so the aggregate task belongs here too");
    }

    @Test
    void everyTaskIsAVerificationTaskWithADescription(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);

        for (String name : List.of(JzapPlugin.ANALYSE_TASK, JzapPlugin.DIFF_TASK,
                JzapPlugin.AGGREGATE_TASK)) {
            var task = project.getTasks().getByName(name);
            assertEquals("verification", task.getGroup(), name + " should be a verification task");
            assertNotNull(task.getDescription(), name + " needs a description for `gradle tasks`");
            assertFalse(task.getDescription().isBlank(), name);
        }
    }

    @Test
    void theAggregateTaskOnlyExistsOnTheRoot(@TempDir Path dir) {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        Project child = ProjectBuilder.builder()
                .withName("child").withParent(root).build();
        child.getPluginManager().apply("io.github.huyz0.jzap");

        assertNull(child.getTasks().findByName(JzapPlugin.AGGREGATE_TASK),
                "one aggregate run per build, registered on the root");
        assertNotNull(child.getTasks().findByName(JzapPlugin.ANALYSE_TASK),
                "but every module still gets its own");
    }

    // ------------------------------------------------------------ conventions

    @Test
    void theExtensionIsRegisteredUnderItsName(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);

        JzapExtension byName = (JzapExtension) project.getExtensions()
                .getByName(JzapPlugin.EXTENSION_NAME);
        assertSame(byName, project.getExtensions().getByType(JzapExtension.class));
    }

    @Test
    void defaultsAreLineScopeAndThreeReporters(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        JzapExtension extension = project.getExtensions().getByType(JzapExtension.class);

        assertEquals("line", extension.getScope().get(),
                "diff scoping defaults to lines, which is the whole point of the tool");
        assertEquals(List.of("console", "json", "html"), extension.getReporters().get());
        assertNull(extension.getThreshold(), "no threshold unless the build asks for one");
    }

    @Test
    void theEngineVersionDefaultsToThePluginsOwn(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        JzapExtension extension = project.getExtensions().getByType(JzapExtension.class);

        assertEquals("0.1.0-SNAPSHOT", extension.getEngineVersion().get(),
                "an unversioned project still needs a resolvable coordinate");

        Project versioned = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        versioned.setVersion("9.9.9");
        versioned.getPluginManager().apply("io.github.huyz0.jzap");
        assertEquals("9.9.9", versioned.getExtensions()
                .getByType(JzapExtension.class).getEngineVersion().get());
    }

    @Test
    void theDiffTaskDefaultsToUncommittedWorkSinceHead(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        JzapTask diff = (JzapTask) project.getTasks().getByName(JzapPlugin.DIFF_TASK);

        assertEquals("HEAD", diff.getFrom().get());
        assertEquals("-Local-", diff.getTo().get(),
                "the pull-request case includes work that is not committed yet");
    }

    @Test
    void theFullTaskHasNoRefsSoItAnalysesEverything(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        JzapTask full = (JzapTask) project.getTasks().getByName(JzapPlugin.ANALYSE_TASK);

        assertFalse(full.getFrom().isPresent(),
                "mutationTest is the whole-codebase run; a ref would silently narrow it");
        assertFalse(full.getTo().isPresent());
    }

    @Test
    void theTwoTasksReportToDifferentDirectories(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        JzapTask full = (JzapTask) project.getTasks().getByName(JzapPlugin.ANALYSE_TASK);
        JzapTask diff = (JzapTask) project.getTasks().getByName(JzapPlugin.DIFF_TASK);

        assertFalse(full.getReportDir().get().getAsFile().equals(
                        diff.getReportDir().get().getAsFile()),
                "a diff run must not overwrite the full run's report: they mean different things");
    }

    @Test
    void theModuleIdIsTheProjectPath(@TempDir Path dir) {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        Project child = ProjectBuilder.builder().withName("child").withParent(root).build();
        child.getPluginManager().apply("io.github.huyz0.jzap");

        JzapTask task = (JzapTask) child.getTasks().getByName(JzapPlugin.ANALYSE_TASK);
        assertEquals(":child", task.getModuleId().get(),
                "the model keys modules by path, so reports can name them the way the build does");
    }

    @Test
    void theTaskIsPointedAtTheSourceSetsOutput(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        JzapTask task = (JzapTask) project.getTasks().getByName(JzapPlugin.ANALYSE_TASK);

        assertTrue(task.getMutableCodePaths().getFiles().stream()
                        .anyMatch(f -> f.getPath().replace('\\', '/').contains("classes/java/main")),
                "what to mutate comes from the main source set: "
                        + task.getMutableCodePaths().getFiles());
        assertTrue(task.getTestClassPaths().getFiles().stream()
                        .anyMatch(f -> f.getPath().replace('\\', '/').contains("classes/java/test")),
                "and the tests from the test source set: " + task.getTestClassPaths().getFiles());
    }

    @Test
    void theEngineConfigurationIsResolvableAndNotConsumable(@TempDir Path dir) {
        Project project = projectWithPlugin(dir);
        var engine = project.getConfigurations().getByName("jzapEngine");

        assertTrue(engine.isCanBeResolved(), "the plugin has to resolve the engine to run it");
        assertFalse(engine.isCanBeConsumed(),
                "nothing should be able to depend on this project for an engine");
        assertNotNull(engine.getDescription(),
                "a configuration a user may meet in `gradle dependencies` needs one");
    }

    // ------------------------------------------------------------ the model fragment

    @Test
    void aModuleFragmentIsWellFormedJsonWithEveryPath(@TempDir Path dir) {
        String fragment = JzapTask.moduleFragment(
                ":lib",
                List.of(new File(dir.toFile(), "classes/main")),
                List.of(new File(dir.toFile(), "src/main/java")),
                List.of(new File(dir.toFile(), "classes/test")),
                List.of(new File(dir.toFile(), "deps/junit.jar")),
                List.of("-Xmx512m", "-Dfoo=bar"));

        assertTrue(fragment.contains("\"id\": \":lib\""), fragment);
        assertTrue(fragment.contains("classes/main") || fragment.contains("classes\\\\main"),
                fragment);
        assertTrue(fragment.contains("src/main/java") || fragment.contains("src\\\\main\\\\java"),
                fragment);
        assertTrue(fragment.contains("junit.jar"), fragment);
        assertTrue(fragment.contains("\"-Xmx512m\", \"-Dfoo=bar\""),
                "jvm args are passed through in order: " + fragment);
        assertEquals(countOf(fragment, '{'), countOf(fragment, '}'), fragment);
        assertEquals(countOf(fragment, '['), countOf(fragment, ']'), fragment);
    }

    @Test
    void anEmptyFragmentStillProducesValidJson() {
        String fragment = JzapTask.moduleFragment(":empty",
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertTrue(fragment.contains("\"mutableCodePaths\": []"), fragment);
        assertTrue(fragment.contains("\"jvmArgs\": []"), fragment);
    }

    @Test
    void backslashesInPathsAreEscapedSoWindowsModelsParse(@TempDir Path dir) {
        // A Windows path would otherwise produce \c, which is not a legal JSON escape.
        String fragment = JzapTask.moduleFragment(":win",
                List.of(new File("C:\\build\\classes")),
                List.of(), List.of(), List.of(), List.of());

        assertFalse(fragment.contains("C:\\build"),
                "a raw backslash makes the model unparseable: " + fragment);
    }

    private static long countOf(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
