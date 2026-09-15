package io.github.huyz0.jzap.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

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
 * Runs mutation testing over this module.
 *
 * <p>Like the Gradle plugin, this contains no analysis logic. Its whole job is turning what Maven
 * already knows -- the output directories, the test classpath, the source roots -- into the one
 * project model document the engine reads, and then running the engine in a JVM of its own.
 * Forking keeps the engine and its bytecode library off Maven's own classpath, and lets the
 * engine version move independently of the plugin's.
 */
@Mojo(name = "mutationCoverage",
        defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class MutationCoverageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Classpath of the jzap engine, as path-separated entries.
     *
     * <p>Required until the engine is published: without a repository to resolve it from, there
     * is nothing for the plugin to fetch, and guessing would fail later and less clearly.
     */
    @Parameter(property = "jzap.engineClasspath", required = true)
    private String engineClasspath;

    @Parameter(property = "jzap.reportDir", defaultValue = "${project.build.directory}/jzap")
    private File reportDir;

    @Parameter(property = "jzap.reporters", defaultValue = "console,json,html")
    private String reporters;

    /**
     * Analysis JVMs. Zero leaves it to the engine, which uses one.
     *
     * <p>Raise it for a slow or I/O-bound suite, where an extra JVM gains almost linearly. On
     * fast CPU-bound tests it can cost more than it saves, so this is a measurement rather than
     * an optimisation.
     */
    @Parameter(property = "jzap.threads", defaultValue = "0")
    private int threads;

    /** Base git ref for diff scoping. Leaving this unset analyses everything. */
    @Parameter(property = "jzap.from")
    private String from;

    /** Tip git ref for diff scoping. {@code -Local-} means uncommitted changes. */
    @Parameter(property = "jzap.to")
    private String to;

    /** {@code line} to mutate only changed lines, {@code class} to widen to changed classes. */
    @Parameter(property = "jzap.scope", defaultValue = "line")
    private String scope;

    @Parameter(property = "jzap.mutators")
    private String mutators;

    @Parameter(property = "jzap.includeClasses")
    private String includeClasses;

    @Parameter(property = "jzap.excludeClasses")
    private String excludeClasses;

    /** Directory holding the incremental cache. Unset means no caching. */
    @Parameter(property = "jzap.cacheDir")
    private File cacheDir;

    /** Fail the build if the mutation score falls below this percentage. */
    @Parameter(property = "jzap.threshold")
    private Double threshold;

    @Parameter(property = "jzap.failOnSurvivors", defaultValue = "false")
    private boolean failOnSurvivors;

    @Parameter(property = "jzap.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "jzap.jvmArgs")
    private String jvmArgs;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("jzap: skipped");
            return;
        }
        Path classes = Path.of(project.getBuild().getOutputDirectory());
        if (!Files.isDirectory(classes)) {
            getLog().info("jzap: nothing compiled to mutate in " + project.getArtifactId());
            return;
        }

        Path model = reportDir.toPath().resolve("jzap-model.json");
        writeModel(model);

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", engineClasspath,
                "io.github.huyz0.jzap.cli.Main", "run",
                "-m", model.toAbsolutePath().toString(),
                "-o", reportDir.getAbsolutePath()));
        if (from != null) {
            command.addAll(List.of("--from", from));
        }
        if (to != null) {
            command.addAll(List.of("--to", to));
        }
        if (cacheDir != null) {
            command.addAll(List.of("--cache-dir", cacheDir.getAbsolutePath()));
        }
        if (threshold != null) {
            command.addAll(List.of("--threshold", threshold.toString()));
        }
        if (failOnSurvivors) {
            command.add("--fail-on-survivors");
        }

        int exit = run(command);
        if (exit == 1) {
            // The engine distinguishes "analysis ran and the result is below your bar" from
            // "analysis failed", and so must the build failure the user sees.
            throw new MojoFailureException("jzap: mutation testing did not meet the configured "
                    + "threshold. The report is at " + reportDir.getAbsolutePath());
        }
        if (exit != 0) {
            throw new MojoExecutionException("jzap: analysis failed with exit code " + exit
                    + ". See the output above.");
        }
    }

    private int run(List<String> command) throws MojoExecutionException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(project.getBasedir())
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLog().info(line);
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot start the jzap engine", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("interrupted while running jzap", e);
        }
    }

    private void writeModel(Path model) throws MojoExecutionException {
        List<String> testClasspath;
        try {
            testClasspath = project.getTestClasspathElements();
        } catch (Exception e) {
            throw new MojoExecutionException("cannot resolve the test classpath", e);
        }

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
                    "mutators": []
                  },
                  "reporters": [%s],
                  "threads": %s
                }
                """.formatted(
                quote(project.getGroupId() + ":" + project.getArtifactId()),
                quote(project.getBuild().getOutputDirectory()),
                list(project.getCompileSourceRoots()),
                quote(project.getBuild().getTestOutputDirectory()),
                list(testClasspath),
                split(jvmArgs),
                from != null || to != null ? "DIFF" : "ALL",
                quote(scope == null ? "line" : scope),
                split(includeClasses),
                split(excludeClasses),
                split(reporters),
                // Rendered with %s and Integer.toString rather than %d: String.formatted uses the
                // default locale, and %d writes digits in that locale's own number system, which
                // would put characters into the model that no JSON parser accepts.
                Integer.toString(threads));
        try {
            Files.createDirectories(model.getParent());
            Files.writeString(model, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the jzap project model to " + model, e);
        }
        if (mutators != null && !mutators.isBlank()) {
            getLog().warn("jzap: mutators are configured but not yet passed through; "
                    + "the default set will be used");
        }
    }

    private static String list(List<String> values) {
        return values.stream().map(MutationCoverageMojo::quote).collect(Collectors.joining(", "));
    }

    private static String split(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MutationCoverageMojo::quote)
                .collect(Collectors.joining(", "));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
