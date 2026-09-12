package io.github.huyz0.jzap.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real Gradle build with the plugin applied, against a project whose expected result is
 * known by hand.
 *
 * <p>The point of testing at this level is that the plugin's only job is producing a correct
 * project model, and the only way to know the model is correct is to analyse something with it
 * and check the answer.
 */
class JzapPluginTest {

    @TempDir
    Path projectDir;

    private static String engineLib() {
        String lib = System.getProperty("jzap.engine.lib");
        if (lib == null) {
            throw new IllegalStateException("jzap.engine.lib is not set; run this through Gradle");
        }
        return lib.replace("\\", "\\\\");
    }

    @BeforeEach
    void writeProject() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'io.github.huyz0.jzap'
                }

                repositories { mavenCentral() }

                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.14.0'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.14.0'
                }

                test { useJUnitPlatform() }

                jzap {
                    engineClasspath.setFrom(fileTree('%s') { include '*.jar' })
                    reporters = ['console', 'json']
                    threads = 2
                }
                """.formatted(engineLib()));

        Path main = projectDir.resolve("src/main/java/demo");
        Files.createDirectories(main);
        Files.writeString(main.resolve("Rules.java"), """
                package demo;

                public class Rules {
                    public boolean allowed(int age) {
                        return age >= 18;
                    }

                    public int doubled(int value) {
                        return value * 2;
                    }
                }
                """);

        Path test = projectDir.resolve("src/test/java/demo");
        Files.createDirectories(test);
        Files.writeString(test.resolve("RulesTest.java"), """
                package demo;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class RulesTest {
                    @Test
                    void checksAge() {
                        assertTrue(new Rules().allowed(18));
                        assertFalse(new Rules().allowed(17));
                    }

                    @Test
                    void doubles() {
                        assertEquals(8, new Rules().doubled(4));
                    }
                }
                """);
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .forwardOutput();
    }

    @Test
    void analysesTheProjectAndWritesAReport() throws IOException {
        BuildResult result = runner("mutationTest", "--stacktrace").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":mutationTest").getOutcome());
        Path report = projectDir.resolve("build/reports/jzap/jzap-result.json");
        assertTrue(Files.exists(report), "no report at " + report);

        String json = Files.readString(report);
        // allowed() is asserted at the boundary and either side of it, so moving the boundary
        // and negating the conditional are both caught. doubled() is fully asserted too, so a
        // well-tested project should come out at 100%.
        assertTrue(json.contains("\"mutationScore\": 100.0000"),
                () -> "expected a perfect score for a fully asserted project:\n" + json);
        assertTrue(result.getOutput().contains("Mutation score 100.0%"), result.getOutput());
    }

    @Test
    void isUpToDateWhenNothingChanged() {
        runner("mutationTest").build();

        BuildResult second = runner("mutationTest").build();

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":mutationTest").getOutcome(),
                "a second run with no changes must not repeat the analysis");
    }

    @Test
    void rerunsWhenTheCodeChanges() throws IOException {
        runner("mutationTest").build();

        Files.writeString(projectDir.resolve("src/main/java/demo/Rules.java"), """
                package demo;

                public class Rules {
                    public boolean allowed(int age) {
                        return age >= 18;
                    }

                    public int doubled(int value) {
                        return value * 2;
                    }

                    public int untested(int value) {
                        return value + 1;
                    }
                }
                """);

        BuildResult second = runner("mutationTest").build();

        assertEquals(TaskOutcome.SUCCESS, second.task(":mutationTest").getOutcome());
        assertTrue(second.getOutput().contains("no coverage"), second.getOutput());
    }

    @Test
    void thresholdFailsTheBuildWithAnActionableMessage() throws IOException {
        Path buildFile = projectDir.resolve("build.gradle");
        Files.writeString(buildFile, Files.readString(buildFile)
                .replace("threads = 2", "threads = 2\n    threshold = 100.0"));
        // Weaken the suite so the score drops below the threshold.
        Files.writeString(projectDir.resolve("src/test/java/demo/RulesTest.java"), """
                package demo;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                class RulesTest {
                    @Test
                    void checksAge() {
                        assertTrue(new Rules().allowed(18));
                    }
                }
                """);

        BuildResult result = runner("mutationTest").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":mutationTest").getOutcome(),
                result.getOutput());
        assertTrue(result.getOutput().contains("did not meet the configured threshold"),
                result.getOutput());
        assertTrue(result.getOutput().contains("The report is at"),
                "the failure must say where to look: " + result.getOutput());
    }

    @Test
    void diffTaskAnalysesOnlyChangedLines() throws IOException {
        // A real repository, because the diff task resolves a git range rather than a patch.
        run("git", "init", "--initial-branch=main");
        run("git", "config", "user.email", "test@example.com");
        run("git", "config", "user.name", "Test");
        run("git", "add", ".");
        run("git", "commit", "-m", "base");

        Files.writeString(projectDir.resolve("src/main/java/demo/Rules.java"),
                Files.readString(projectDir.resolve("src/main/java/demo/Rules.java"))
                        .replace("return value * 2;", "return value * 3;"));
        Files.writeString(projectDir.resolve("src/test/java/demo/RulesTest.java"),
                Files.readString(projectDir.resolve("src/test/java/demo/RulesTest.java"))
                        .replace("assertEquals(8,", "assertEquals(12,"));

        BuildResult result = runner("mutationTestDiff", "--stacktrace").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":mutationTestDiff").getOutcome());
        String json = Files.readString(
                projectDir.resolve("build/reports/jzap-diff/jzap-result.json"));
        assertTrue(json.contains("doubled"), "the changed method should be analysed:\n" + json);
        assertTrue(!json.contains("\"method\": \"allowed(I)Z\""),
                "the untouched method should not be analysed:\n" + json);
    }

    /**
     * With no local engine configured the plugin falls back to resolving a published one, which
     * does not exist in this repository. The failure is Gradle's own, by design: see
     * JzapTask#getEngineClasspath for why it is not wrapped.
     */
    @Test
    void missingEngineFailsNamingTheConfigurationAndTheCoordinate() throws IOException {
        Path buildFile = projectDir.resolve("build.gradle");
        Files.writeString(buildFile, Files.readString(buildFile)
                .replace("engineClasspath.setFrom(fileTree('" + engineLib() + "') { include '*.jar' })",
                        "engineClasspath.setFrom(files())"));

        BuildResult result = runner("mutationTest").buildAndFail();

        assertTrue(result.getOutput().contains("jzapEngine"),
                "the failure must name the configuration: " + result.getOutput());
        assertTrue(result.getOutput().contains("io.github.huyz0:jzap-cli"),
                "and the coordinate it could not find: " + result.getOutput());
    }

    private void run(String... command) throws IOException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The model the plugin writes must be usable on its own.
     *
     * <p>This is the conformance check that matters: the plugin's entire contribution is that
     * document, so if the engine can be driven from it directly then the adapter is correct and
     * any later disagreement is the engine's. It is also how a user reproduces a plugin problem
     * without Gradle in the loop.
     */
    @Test
    void theModelThePluginWritesDrivesTheEngineOnItsOwn() throws IOException {
        runner("mutationTest").build();

        Path model = projectDir.resolve("build/reports/jzap/jzap-model.json");
        assertTrue(Files.exists(model), "the plugin did not write a model at " + model);
        String json = Files.readString(model);
        assertTrue(json.contains("\"schemaVersion\": 1"), json);
        assertTrue(json.contains("\"id\": \":\""), "module id should be the project path:\n" + json);

        Path standalone = projectDir.resolve("build/standalone");
        int exit = runEngine(model, standalone);

        assertEquals(0, exit, "the engine could not run from the plugin's own model");
        String viaPlugin = withoutTimings(
                Files.readString(projectDir.resolve("build/reports/jzap/jzap-result.json")));
        String viaCli = withoutTimings(Files.readString(standalone.resolve("jzap-result.json")));
        assertEquals(viaPlugin, viaCli,
                "driving the engine through the plugin and directly must agree");
    }

    private static String withoutTimings(String json) {
        return json.substring(0, json.indexOf("\"timings\""));
    }

    private int runEngine(Path model, Path reportDir) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("jzap.engine.lib") + "/*",
                "io.github.huyz0.jzap.cli.Main", "run",
                "-m", model.toAbsolutePath().toString(),
                "-o", reportDir.toAbsolutePath().toString(),
                "-q"));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                System.out.println(output);
            }
            return exit;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
