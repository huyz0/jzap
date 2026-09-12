package io.github.huyz0.jzap.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.util.List;

/**
 * Registers the {@code mutationTest} and {@code mutationTestDiff} tasks.
 *
 * <p>The plugin resolves classpaths and toolchains and nothing else. Every decision about what
 * a mutant is, which tests to run, and how to report belongs to the engine, which is why this
 * file is short and is expected to stay short.
 */
public class JzapPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "jzap";
    public static final String ANALYSE_TASK = "mutationTest";
    public static final String DIFF_TASK = "mutationTestDiff";
    private static final String ENGINE_CONFIGURATION = "jzapEngine";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        JzapExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, JzapExtension.class);
        extension.getEngineVersion().convention(versionOf(project));
        extension.getScope().convention("line");
        extension.getReporters().convention(List.of("console", "json", "html"));

        Configuration engine = project.getConfigurations().create(ENGINE_CONFIGURATION, it -> {
            it.setCanBeConsumed(false);
            it.setCanBeResolved(true);
            it.setDescription("The jzap engine, resolved separately from the plugin so its "
                    + "version can be changed without changing the plugin's.");
            it.defaultDependencies(dependencies -> dependencies.add(
                    project.getDependencies().create(
                            "io.github.huyz0:jzap-cli:" + extension.getEngineVersion().get())));
        });

        SourceSet main = sourceSets(project).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = sourceSets(project).getByName(SourceSet.TEST_SOURCE_SET_NAME);

        project.getTasks().register(ANALYSE_TASK, JzapTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs mutation testing over this module.");
            configure(task, project, extension, engine, main, test);
        });

        project.getTasks().register(DIFF_TASK, JzapTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs mutation testing over lines changed since the base ref.");
            configure(task, project, extension, engine, main, test);
            // Defaults chosen for the pull-request case: everything since the merge base of the
            // main branch, including work that is not committed yet.
            task.getFrom().convention(extension.getFrom().convention("HEAD"));
            task.getTo().convention(extension.getTo().convention("-Local-"));
            task.getReportDir().convention(
                    project.getLayout().getBuildDirectory().dir("reports/jzap-diff"));
        });
    }

    private void configure(JzapTask task, Project project, JzapExtension extension,
                           Configuration engine, SourceSet main, SourceSet test) {
        task.dependsOn(main.getOutput(), test.getOutput());

        task.getEngineClasspath().setFrom(project.provider(() ->
                extension.getEngineClasspath().isEmpty() ? engine : extension.getEngineClasspath()));
        task.getMutableCodePaths().setFrom(main.getOutput().getClassesDirs());
        task.getTestClassPaths().setFrom(test.getOutput().getClassesDirs());
        task.getTestClasspath().setFrom(test.getRuntimeClasspath());
        task.getSourceRoots().setFrom(project.provider(() -> main.getAllJava().getSrcDirs()));

        task.getModuleId().set(project.getPath());
        task.getThreads().set(extension.getThreads());
        task.getReporters().set(extension.getReporters());
        task.getScope().set(extension.getScope());
        task.getIncludeClasses().set(extension.getIncludeClasses());
        task.getExcludeClasses().set(extension.getExcludeClasses());
        task.getMutators().set(extension.getMutators());
        task.getMutateLoopCounters().set(extension.getMutateLoopCounters());
        task.getThreshold().set(project.provider(extension::getThreshold));
        task.getFailOnSurvivors().set(extension.getFailOnSurvivors());
        task.getJvmArgs().set(extension.getJvmArgs());
        task.getReportDir().convention(
                project.getLayout().getBuildDirectory().dir("reports/jzap"));

        // Tests must run on the JVM the build chose, not on whatever is running Gradle. The
        // engine obeys what the model says; resolving it is this plugin's job.
        task.getJavaExecutable().set(project.provider(() -> {
            JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
            if (java == null || !java.getToolchain().getLanguageVersion().isPresent()) {
                return null;
            }
            return project.getExtensions()
                    .getByType(org.gradle.jvm.toolchain.JavaToolchainService.class)
                    .launcherFor(java.getToolchain())
                    .get().getExecutablePath().getAsFile().getAbsolutePath();
        }));
    }

    private static SourceSetContainer sourceSets(Project project) {
        return project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
    }

    private static String versionOf(Project project) {
        String version = String.valueOf(project.getVersion());
        return "unspecified".equals(version) ? "0.1.0-SNAPSHOT" : version;
    }
}
