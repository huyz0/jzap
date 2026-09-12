package io.github.huyz0.jzap.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes a project model and runs the engine against it.
 *
 * <p>Declares its inputs and outputs so Gradle can skip it when nothing has changed. Full
 * build-cache relocatability waits on the content-hash keying of M11: the report currently
 * embeds phase timings, which differ between runs by design.
 */
public abstract class JzapTask extends DefaultTask {

    /**
     * The engine to run. Kept off the buildscript classpath on purpose.
     *
     * <p>A single tracked input, so changing the engine re-runs the analysis and the task stays
     * configuration-cache compatible. An unresolvable engine therefore fails during input
     * snapshotting, with Gradle's own message rather than one of ours.
     *
     * <p>That is a deliberate choice, recorded here because it is not the obvious one and three
     * attempts at beating it all failed: wrapping the resolution in the action does not help
     * because {@code @Classpath} inputs are snapshotted first; an {@code @Internal} file
     * collection still gains an implicit task dependency that Gradle resolves before executing;
     * and a non-file {@code Property} fed by a provider is finalised, and therefore queried,
     * before the action too. Each workaround would have traded configuration-cache
     * compatibility for a better sentence. Gradle's message already names the configuration and
     * the missing coordinate, which is the message Gradle users are trained to read; the escape
     * hatch is documented on {@link JzapExtension#getEngineClasspath()} instead.
     */
    @Classpath
    public abstract ConfigurableFileCollection getEngineClasspath();

    /** Compiled classes to mutate. */
    @Classpath
    public abstract ConfigurableFileCollection getMutableCodePaths();

    /** Compiled test classes, scanned for test discovery. */
    @Classpath
    public abstract ConfigurableFileCollection getTestClassPaths();

    /** Everything needed to run the tests, including the test and main output. */
    @Classpath
    public abstract ConfigurableFileCollection getTestClasspath();

    /** Source directories, used to map lines to files and to render reports. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceRoots();

    @Input
    public abstract Property<String> getModuleId();

    @Input
    @Optional
    public abstract Property<String> getJavaExecutable();

    @Input
    @Optional
    public abstract Property<Integer> getThreads();

    @Input
    public abstract ListProperty<String> getReporters();

    @Input
    @Optional
    public abstract Property<String> getFrom();

    @Input
    @Optional
    public abstract Property<String> getTo();

    @Input
    @Optional
    public abstract Property<String> getScope();

    @Input
    public abstract ListProperty<String> getIncludeClasses();

    @Input
    public abstract ListProperty<String> getExcludeClasses();

    @Input
    public abstract ListProperty<String> getMutators();

    @Input
    @Optional
    public abstract Property<Boolean> getMutateLoopCounters();

    @Input
    @Optional
    public abstract Property<Double> getThreshold();

    @Input
    @Optional
    public abstract Property<Boolean> getFailOnSurvivors();

    @Input
    public abstract ListProperty<String> getJvmArgs();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract org.gradle.api.model.ObjectFactory getObjects();

    @TaskAction
    public void analyse() {
        java.util.Set<File> engine = getEngineClasspath().getFiles();
        if (engine.isEmpty()) {
            throw new GradleException("""
                    No jzap engine on the classpath.

                    Either name a published version:
                        jzap { engineVersion = "0.1.0" }
                    or point at a local build:
                        jzap { engineClasspath.setFrom(fileTree("path/to/jzap/lib")) }
                    """);
        }
        if (getMutableCodePaths().getFiles().stream().noneMatch(File::exists)) {
            getLogger().lifecycle("jzap: nothing compiled to mutate in {}", getModuleId().get());
            return;
        }

        Path reportDir = getReportDir().get().getAsFile().toPath();
        Path model = reportDir.resolve("jzap-model.json");
        writeModel(model);

        List<String> arguments = new ArrayList<>(List.of(
                "run", "-m", model.toAbsolutePath().toString(),
                "-o", reportDir.toAbsolutePath().toString()));
        if (getFrom().isPresent()) {
            arguments.addAll(List.of("--from", getFrom().get()));
        }
        if (getTo().isPresent()) {
            arguments.addAll(List.of("--to", getTo().get()));
        }
        if (getMutateLoopCounters().getOrElse(false)) {
            arguments.add("--mutate-loop-counters");
        }
        if (getThreshold().isPresent()) {
            arguments.addAll(List.of("--threshold", Double.toString(getThreshold().get())));
        }
        if (getFailOnSurvivors().getOrElse(false)) {
            arguments.add("--fail-on-survivors");
        }

        java.util.Set<File> engineClasspath = engine;
        var result = getExecOperations().javaexec(spec -> {
            spec.setClasspath(getObjects().fileCollection().from(engineClasspath));
            spec.getMainClass().set("io.github.huyz0.jzap.cli.Main");
            spec.setArgs(arguments);
            if (getJavaExecutable().isPresent()) {
                spec.setExecutable(getJavaExecutable().get());
            }
            spec.setIgnoreExitValue(true);
        });

        int exit = result.getExitValue();
        if (exit == 1) {
            // The engine distinguishes "analysis ran and the result is below your bar" from
            // "analysis failed", and so must the build failure the user sees.
            throw new GradleException("jzap: mutation testing did not meet the configured "
                    + "threshold. The report is at " + reportDir.toAbsolutePath());
        }
        if (exit != 0) {
            throw new GradleException("jzap: analysis failed with exit code " + exit
                    + ". See the output above.");
        }
    }

    private void writeModel(Path model) {
        String json = """
                {
                  "schemaVersion": 1,
                  "modules": [
                    {
                      "id": %s,
                      "mutableCodePaths": [%s],
                      "sourceRoots": [%s],
                      "testClassPaths": [%s],
                      "testClasspath": [%s],
                      "jvmArgs": [%s]
                    }
                  ],
                  "scope": {
                    "kind": "%s",
                    "granularity": %s,
                    "includeClasses": [%s],
                    "excludeClasses": [%s],
                    "mutators": [%s],
                    "disabledFilters": [%s]
                  },
                  "reporters": [%s],
                  "threads": %d
                }
                """.formatted(
                quote(getModuleId().get()),
                paths(getMutableCodePaths()),
                paths(getSourceRoots()),
                paths(getTestClassPaths()),
                paths(getTestClasspath()),
                strings(getJvmArgs().get()),
                getFrom().isPresent() || getTo().isPresent() ? "DIFF" : "ALL",
                quote(getScope().getOrElse("line")),
                strings(getIncludeClasses().get()),
                strings(getExcludeClasses().get()),
                strings(getMutators().get()),
                getMutateLoopCounters().getOrElse(false) ? quote("LOOP_COUNTER") : "",
                strings(getReporters().get()),
                getThreads().getOrElse(0));
        try {
            Files.createDirectories(model.getParent());
            Files.writeString(model, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the jzap project model to " + model, e);
        }
    }

    private static String paths(ConfigurableFileCollection files) {
        return files.getFiles().stream()
                .map(File::getAbsolutePath)
                .map(JzapTask::quote)
                .collect(Collectors.joining(", "));
    }

    private static String strings(List<String> values) {
        return values.stream().map(JzapTask::quote).collect(Collectors.joining(", "));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
