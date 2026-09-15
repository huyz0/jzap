package io.github.huyz0.jzap.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    public static final String AGGREGATE_TASK = "mutationTestAll";
    /**
     * Environment variables supplying a diff range.
     *
     * <p>For CI, which knows the base branch and cannot edit the build script to say so. Every
     * other option is settable in the {@code jzap} block because it is a property of the project;
     * the range is a property of the invocation, and is the one thing a pull-request build has to
     * decide per run.
     */
    public static final String FROM_ENV = "JZAP_FROM";
    public static final String TO_ENV = "JZAP_TO";

    private static final String FRAGMENTS_KEY = "jzap.aggregate.fragments";
    private static final String INPUTS_KEY = "jzap.aggregate.inputs";
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

        contributeToAggregate(project, extension, main, test);

        project.getTasks().register(ANALYSE_TASK, JzapTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs mutation testing over this module.");
            configure(task, project, extension, engine, main, test);
        });

        if (project == project.getRootProject()) {
            registerAggregate(project, extension, engine);
        }

        project.getTasks().register(DIFF_TASK, JzapTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs mutation testing over lines changed since the base ref.");
            configure(task, project, extension, engine, main, test);
            // HEAD..-Local- is the developer default: work that is changed but not committed yet.
            // In CI the interesting range is against the base branch instead, which is what
            // JZAP_FROM and JZAP_TO supply without an edit to the build script.
            task.getFrom().convention(diffRef(extension.getFrom(), env(project, FROM_ENV), "HEAD"));
            task.getTo().convention(diffRef(extension.getTo(), env(project, TO_ENV), "-Local-"));
            task.getReportDir().convention(
                    project.getLayout().getBuildDirectory().dir("reports/jzap-diff"));
        });
    }

    /**
     * Registers {@code mutationTestAll}: one invocation covering every module that applies the
     * plugin.
     *
     * <p>Worth having as more than a convenience. Analysed a module at a time, a library module
     * with no tests of its own reports every mutant as uncovered, because the tests that exercise
     * it live next door. One invocation lets a test in any module kill a mutant in any other, and
     * warms each analysis JVM once for the whole reactor rather than once per module.
     *
     * <p>Module descriptions are rendered while the build is configured and carried as strings.
     * Reaching across to another project at execution time is what breaks the configuration cache,
     * and a task cannot hold a variable number of file collections anyway.
     */
    private void registerAggregate(Project root, JzapExtension extension, Configuration engine) {
        root.getTasks().register(AGGREGATE_TASK, JzapTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs mutation testing across every module in one pass.");
            task.getReportDir().convention(
                    root.getLayout().getBuildDirectory().dir("reports/jzap-all"));

            // Each project queues its own description as a Provider while it is configured (see
            // contributeToAggregate); this reads the accumulated queue, still lazily, resolving
            // each entry only now -- which for an @Input property means at THIS task's own input
            // snapshotting, inside its own execution-time lock, the same moment the single-module
            // task already safely resolves a cross-project test classpath in configure() below.
            task.getModuleFragments().set(root.provider(() ->
                    fragments(root).stream().map(Provider::get).collect(Collectors.toList())));
            task.getAggregateInputs().from(root.provider(() -> inputs(root)));

            task.getEngineClasspath().setFrom(root.provider(() ->
                    extension.getEngineClasspath().isEmpty() ? engine : extension.getEngineClasspath()));
            task.getModuleId().set(root.getPath());
            task.getThreads().set(extension.getThreads());
            task.getReporters().set(extension.getReporters());
            task.getScope().set(extension.getScope());
            // A range, if one was given. This task read neither the extension's from/to nor the
            // environment before, so a multi-module change could not be analysed in one pass --
            // which is the case cross-module test selection exists for, and the reason the
            // scope granularity set just above had anything to apply to. There is deliberately
            // no HEAD fallback as there is on the diff task: unset means every module in full,
            // which is what a task called mutationTestAll has to keep meaning.
            task.getFrom().convention(extension.getFrom().orElse(env(root, FROM_ENV)));
            task.getTo().convention(extension.getTo().orElse(env(root, TO_ENV)));
            task.getIncludeClasses().set(extension.getIncludeClasses());
            task.getExcludeClasses().set(extension.getExcludeClasses());
            task.getMutators().set(extension.getMutators());
            task.getMutateLoopCounters().set(extension.getMutateLoopCounters());
            task.getThreshold().set(root.provider(extension::getThreshold));
            task.getFailOnSurvivors().set(extension.getFailOnSurvivors());
            task.getJvmArgs().set(extension.getJvmArgs());
            task.getCacheDir().set(extension.getCacheDir());
        });
    }

    /**
     * Records this project's contribution to an aggregate run.
     *
     * <p>The file collections are handed over whole rather than resolved to paths, so the
     * aggregate task inherits the task dependencies that produce them.
     *
     * <p>⚠️ The actual {@code .getFiles()} resolution is queued as a {@link Provider} rather than
     * run here, and that is load-bearing. {@code Project.afterEvaluate} fires once per project, as
     * soon as <em>that</em> project finishes evaluating -- which can be, and in a module graph
     * with a {@code testFixtures(project(...))} dependency routinely is, before a sibling project
     * it depends on has registered its own components. Calling {@code .getFiles()} here, eagerly,
     * throws {@code IllegalStateException: Value for :<sibling> project components has not been
     * calculated yet}, in every build regardless of whether {@code mutationTestAll} is ever
     * invoked, because this method runs unconditionally for every project that applies the
     * plugin. {@code configured.provider(() -> ...)} defers that same call instead: the queue this
     * adds to is read back, still lazily, only when {@code mutationTestAll}'s own {@code
     * getModuleFragments()} is resolved -- i.e. at THAT task's input snapshotting, which is both
     * after every project has finished evaluating and inside that task's own execution-time lock.
     * (A {@code Gradle.projectsEvaluated} listener gets the first property but not the second: it
     * runs outside any project's lock, and resolving a {@code Configuration} from it throws
     * "attempted without an exclusive lock" instead -- measured, not assumed.) The queued
     * {@code Provider<String>} still resolves to a plain, serializable {@code String} for the
     * task's {@code @Input}, so the class javadoc's configuration-cache promise still holds.
     */
    private void contributeToAggregate(Project project, JzapExtension extension,
                                       SourceSet main, SourceSet test) {
        project.afterEvaluate(configured -> {
            if (main.getAllJava().isEmpty() && test.getAllJava().isEmpty()) {
                // A project that only carries configuration, which an aggregate root usually is.
                return;
            }
            Project root = configured.getRootProject();
            inputs(root).add(main.getOutput().getClassesDirs());
            inputs(root).add(test.getRuntimeClasspath());
            fragments(root).add(configured.provider(() -> JzapTask.moduleFragment(
                    configured.getPath(),
                    main.getOutput().getClassesDirs().getFiles(),
                    main.getAllJava().getSrcDirs(),
                    test.getOutput().getClassesDirs().getFiles(),
                    test.getRuntimeClasspath().getFiles(),
                    extension.getJvmArgs().getOrElse(List.of()))));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Provider<String>> fragments(Project root) {
        ExtraPropertiesExtension extra = root.getExtensions().getExtraProperties();
        if (!extra.has(FRAGMENTS_KEY)) {
            extra.set(FRAGMENTS_KEY, new ArrayList<Provider<String>>());
        }
        return (List<Provider<String>>) extra.get(FRAGMENTS_KEY);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> inputs(Project root) {
        ExtraPropertiesExtension extra = root.getExtensions().getExtraProperties();
        if (!extra.has(INPUTS_KEY)) {
            extra.set(INPUTS_KEY, new ArrayList<Object>());
        }
        return (List<Object>) extra.get(INPUTS_KEY);
    }

    /**
     * A diff ref: the build script's value first, then the environment, then a default.
     *
     * <p>Precedence in that order because the build script is the explicit statement and the
     * environment is the ambient one. A project that has committed to a range in its own
     * configuration should not have it changed by whatever is exported into the shell.
     *
     * <p>Note what this does not do: it does not call {@code convention()} on the extension's
     * property. That is how the fallback used to be supplied, and it writes through to the
     * property every other task shares -- so merely configuring the diff task would have handed
     * {@code mutationTestAll} a range nobody asked for, silently turning a full reactor run into
     * a diff against HEAD.
     */
    private static Provider<String> diffRef(Property<String> configured,
                                            Provider<String> environment, String fallback) {
        return configured.orElse(environment).orElse(fallback);
    }

    /**
     * Reads an environment variable as a tracked build input.
     *
     * <p>Through Gradle's provider rather than {@code System.getenv}, which the configuration
     * cache cannot see. An untracked read would let a cached configuration keep serving the
     * previous run's range, and would leave the task up-to-date across a change of range -- so
     * a pull-request build would report the verdicts of whatever was analysed last.
     */
    private static Provider<String> env(Project project, String name) {
        return project.getProviders().environmentVariable(name);
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
        task.getCacheDir().set(extension.getCacheDir());
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
