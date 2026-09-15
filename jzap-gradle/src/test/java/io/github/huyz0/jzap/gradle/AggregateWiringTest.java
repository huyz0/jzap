package io.github.huyz0.jzap.gradle;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.provider.Provider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How each module contributes to the one aggregate run.
 *
 * <p>This is worth more than convenience. Analysed a module at a time, a library module with no
 * tests of its own reports every mutant as uncovered, because the tests that exercise it live
 * next door -- the ordinary shape of a multi-module project, and a score that is simply wrong.
 * One invocation lets a test in any module kill a mutant in any other.
 *
 * <p>The contributions are gathered during configuration and carried as strings, because reaching
 * across to another project at execution time breaks the configuration cache. A test that only
 * checked the task existed would miss all of that.
 */
class AggregateWiringTest {

    /** A project with the plugin applied and one Java source file, then evaluated. */
    private static Project module(Project parent, String name, Path dir) throws Exception {
        Path projectDir = dir.resolve(name);
        Path source = projectDir.resolve("src/main/java/pkg/" + name + "Class.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package pkg; public class " + name + "Class {}");

        Project project = ProjectBuilder.builder()
                .withName(name).withParent(parent).withProjectDir(projectDir.toFile()).build();
        project.getPluginManager().apply("io.github.huyz0.jzap");
        return project;
    }

    private static void evaluate(Project project) {
        ((ProjectInternal) project).evaluate();
    }

    /**
     * The contributions gathered so far; absent and empty mean the same thing to a caller.
     *
     * <p>Stored as {@code Provider<String>}, not {@code String}: resolving {@code .getFiles()}
     * here, at configuration time, is what threw "project components has not been calculated yet"
     * against a sibling project this one depends on through {@code testFixtures(project(...))} --
     * see the javadoc on {@code JzapPlugin.contributeToAggregate}. Resolving each provider here,
     * in a unit test that evaluates every module up front, is fine; it is the production code path
     * (evaluation interleaved with a sibling not yet ready) that resolving early cannot survive.
     */
    @SuppressWarnings("unchecked")
    private static List<String> fragments(Project root) {
        var extra = root.getExtensions().getExtraProperties();
        return extra.has("jzap.aggregate.fragments")
                ? ((List<Provider<String>>) extra.get("jzap.aggregate.fragments")).stream()
                        .map(Provider::get)
                        .collect(Collectors.toList())
                : List.of();
    }

    @Test
    void eachModuleContributesItsOwnDescription(@TempDir Path dir) throws Exception {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        Project core = module(root, "core", dir);
        Project app = module(root, "app", dir);

        evaluate(core);
        evaluate(app);

        List<String> fragments = fragments(root);
        assertEquals(2, fragments.size(), "one per module that applied the plugin: " + fragments);
        assertTrue(fragments.stream().anyMatch(f -> f.contains("\":core\"")), fragments.toString());
        assertTrue(fragments.stream().anyMatch(f -> f.contains("\":app\"")), fragments.toString());
    }

    @Test
    void theAggregateTaskSeesEveryContribution(@TempDir Path dir) throws Exception {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        root.getPluginManager().apply("io.github.huyz0.jzap");
        Project core = module(root, "core", dir);
        evaluate(core);

        JzapTask aggregate = (JzapTask) root.getTasks().getByName(JzapPlugin.AGGREGATE_TASK);

        assertTrue(aggregate.getModuleFragments().get().stream()
                        .anyMatch(f -> f.contains("\":core\"")),
                "the task reads the accumulated list lazily: " + aggregate.getModuleFragments().get());
    }

    @Test
    void aProjectThatOnlyCarriesConfigurationContributesNothing(@TempDir Path dir) {
        // An aggregate root usually has no sources of its own, and a fragment for it would
        // describe a module with nothing to mutate.
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        root.getPluginManager().apply("io.github.huyz0.jzap");

        evaluate(root);

        assertTrue(fragments(root).isEmpty(),
                "nothing to mutate and nothing to test means nothing to describe, and the "
                        + "aggregate list is never even created");
    }

    @Test
    void aModuleWithOnlyTestsStillContributes(@TempDir Path dir) throws Exception {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        Path projectDir = dir.resolve("tests-only");
        Path source = projectDir.resolve("src/test/java/pkg/OnlyTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package pkg; public class OnlyTest {}");
        Project testsOnly = ProjectBuilder.builder()
                .withName("tests-only").withParent(root)
                .withProjectDir(projectDir.toFile()).build();
        testsOnly.getPluginManager().apply("io.github.huyz0.jzap");

        evaluate(testsOnly);

        assertFalse(fragments(root).isEmpty(),
                "its tests can kill mutants in another module, which is the whole point");
    }

    @Test
    void theAggregateTaskTakesItsSettingsFromTheRootExtension(@TempDir Path dir) {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        root.getPluginManager().apply("io.github.huyz0.jzap");
        JzapExtension extension = root.getExtensions().getByType(JzapExtension.class);
        extension.getThreads().set(6);
        extension.getReporters().set(List.of("json"));
        extension.setThreshold(75);
        extension.getFailOnSurvivors().set(true);
        extension.getMutators().set(List.of("MATH"));

        JzapTask aggregate = (JzapTask) root.getTasks().getByName(JzapPlugin.AGGREGATE_TASK);

        assertEquals(6, aggregate.getThreads().get());
        assertEquals(List.of("json"), aggregate.getReporters().get());
        assertEquals(75.0, aggregate.getThreshold().get());
        assertTrue(aggregate.getFailOnSurvivors().get());
        assertEquals(List.of("MATH"), aggregate.getMutators().get());
    }

    @Test
    void theAggregateTaskReportsSomewhereOfItsOwn(@TempDir Path dir) {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        root.getPluginManager().apply("io.github.huyz0.jzap");

        JzapTask aggregate = (JzapTask) root.getTasks().getByName(JzapPlugin.AGGREGATE_TASK);
        JzapTask perModule = (JzapTask) root.getTasks().getByName(JzapPlugin.ANALYSE_TASK);

        assertFalse(aggregate.getReportDir().get().getAsFile()
                        .equals(perModule.getReportDir().get().getAsFile()),
                "an aggregate result covers different mutants from a single-module one");
    }

    @Test
    void theAggregateTaskIsIdentifiedByTheRootPath(@TempDir Path dir) {
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        root.getPluginManager().apply("io.github.huyz0.jzap");

        JzapTask aggregate = (JzapTask) root.getTasks().getByName(JzapPlugin.AGGREGATE_TASK);

        assertEquals(root.getPath(), aggregate.getModuleId().get());
    }

    @Test
    void aThresholdGivenAsADecimalIsAccepted(@TempDir Path dir) {
        // In a Groovy build script `threshold = 80.0` is a BigDecimal, which is why the setter
        // takes a Number rather than a Double.
        Project root = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        root.getPluginManager().apply("io.github.huyz0.jzap");
        JzapExtension extension = root.getExtensions().getByType(JzapExtension.class);

        extension.setThreshold(new java.math.BigDecimal("80.5"));
        assertEquals(80.5, extension.getThreshold());

        extension.setThreshold(null);
        assertEquals(null, extension.getThreshold(), "unset has to stay distinguishable from zero");
    }
}
