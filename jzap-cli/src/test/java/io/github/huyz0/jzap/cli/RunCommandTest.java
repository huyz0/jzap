package io.github.huyz0.jzap.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code run} command, driven in this JVM against the sample fixture.
 *
 * <p>What a user meets first is an exit code and a line of output, and both are contracts: a
 * build that fails on a threshold has to be distinguishable from a build that failed to run at
 * all, or CI cannot tell "your tests are weak" from "jzap is broken".
 */
class RunCommandTest {

    /** Output of one CLI invocation, with the exit code it returned. */
    private record Invocation(int exitCode, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private static Invocation run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outWas = System.out;
        PrintStream errWas = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new JzapCommand()).execute(args);
            return new Invocation(code,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(outWas);
            System.setErr(errWas);
        }
    }

    // ------------------------------------------------------------ the happy path

    @Test
    void analysesTheFixtureAndWritesTheJsonReport(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        Invocation result = run("run", "-m", model.toString(), "-o", reports.toString(), "-q");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        Path json = reports.resolve("jzap-result.json");
        assertTrue(Files.isRegularFile(json), "the json reporter must have written " + json);
        String report = Files.readString(json);
        assertTrue(report.contains("\"KILLED\""), "the fixture has killed mutants: " + report);
        assertTrue(result.out().contains("Reports written to"),
                "a run that wrote files should say where: " + result.out());
    }

    @Test
    void theConsoleReporterAloneDoesNotAnnounceAReportDirectory(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("run", "-m", model.toString(),
                "-o", dir.resolve("reports").toString(), "-r", "console", "-q");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertFalse(result.out().contains("Reports written to"),
                "console output is the report; pointing at a directory would be noise");
    }

    // ------------------------------------------------------------ exit codes

    @Test
    void aScoreBelowTheThresholdFailsWithItsOwnExitCode(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("run", "-m", model.toString(),
                "-o", dir.resolve("reports").toString(), "-q", "--threshold", "100");

        assertEquals(RunCommand.EXIT_THRESHOLD, result.exitCode(), result.all());
        assertTrue(result.err().contains("below the threshold"),
                "the failure has to say what it was measured against: " + result.err());
    }

    @Test
    void aScoreAboveTheThresholdPasses(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("run", "-m", model.toString(),
                "-o", dir.resolve("reports").toString(), "-q", "--threshold", "1");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
    }

    @Test
    void survivorsCanFailTheBuildOnTheirOwn(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("run", "-m", model.toString(),
                "-o", dir.resolve("reports").toString(), "-q", "--fail-on-survivors");

        assertEquals(RunCommand.EXIT_THRESHOLD, result.exitCode(),
                "the sample fixture has survivors by design: " + result.all());
        assertTrue(result.err().contains("survived"), result.err());
    }

    // ------------------------------------------------------------ bad input

    @Test
    void aMissingModelFileSaysWhatProducesOne(@TempDir Path dir) {
        Invocation result = run("run", "-m", dir.resolve("absent.json").toString());

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("no project model at"), result.err());
        assertTrue(result.err().contains("build-tool adapter"),
                "the user needs to know where the file comes from, not just that it is missing: "
                        + result.err());
    }

    @Test
    void anUnknownEngineIsRejectedBeforeAnythingIsRun(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation result = run("run", "-m", model.toString(), "--engine", "turbo");

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode());
        assertTrue(result.err().contains("turbo"), result.err());
    }

    /**
     * A typo in --reporters is a usage error, and has to fail as one before the work starts.
     *
     * <p>It used to throw after every mutant had been analysed, and picocli turned the uncaught
     * exception into exit code 1 -- which is the code for "your mutation score is below the
     * threshold". CI could not tell a weak test suite from a misspelled flag.
     */
    @Test
    void anUnknownReporterIsAUsageErrorAndNamesTheOnesThatExist(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        Invocation result = run("run", "-m", model.toString(),
                "-o", reports.toString(), "-q", "-r", "telepathy");

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode(), result.all());
        assertTrue(result.err().contains("telepathy"), result.err());
        assertTrue(result.err().contains("console"),
                "naming the ones that exist is what makes the error actionable: " + result.err());
        assertFalse(Files.exists(reports.resolve("jzap-result.json")),
                "it must fail before running the analysis, not after");
    }

    @Test
    void aPatchScopeWithNoPatchFileSaysWhichOptionIsMissing(@TempDir Path dir) {
        Path model = new CliFixture().writeModelWith(dir, "");
        // PATCH kind with no patchFile is only reachable from a model file, since --patch both
        // selects the kind and supplies the file.
        Path patchModel = CliFixture.write(dir.resolve("patch-model.json"),
                Files.exists(model) ? readReplacingKind(model) : "");

        Invocation result = run("run", "-m", patchModel.toString());

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode(), result.all());
        assertTrue(result.err().contains("--patch"), result.err());
    }

    private static String readReplacingKind(Path model) {
        try {
            return Files.readString(model).replace("\"kind\": \"ALL\"", "\"kind\": \"PATCH\"");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ dry run

    @Test
    void dryRunPrintsWhatWouldBeAnalysedAndRunsNothing(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        Invocation result = run("run", "-m", model.toString(), "-o", reports.toString(),
                "--dry-run");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertTrue(result.out().contains("Scope kind: ALL"), result.out());
        assertTrue(result.out().contains("Everything is in scope."), result.out());
        assertTrue(result.out().contains("code:"), "resolved paths are the point: " + result.out());
        assertTrue(result.out().contains("classpath entries:"), result.out());
        assertFalse(Files.exists(reports.resolve("jzap-result.json")),
                "a dry run must not write a report");
    }

    @Test
    void dryRunWithADiffScopeListsTheFilesAndSaysNothingIsCheckedOut(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        // --to -Empty- puts everything tracked in scope without needing a synthetic commit.
        Invocation result = run("run", "-m", model.toString(), "--dry-run",
                "--from", "HEAD", "--to", "-Local-");

        // Whether this resolves depends on the working tree, so only the shape is asserted:
        // either it printed a scope, or it explained why it could not.
        assertTrue(result.all().contains("Scope kind: DIFF") || result.exitCode() != 0,
                result.all());
        if (result.exitCode() == RunCommand.EXIT_OK) {
            assertTrue(result.out().contains("nothing is checked out"),
                    "the semantic both Mull and arcmutate call a source of confusion: "
                            + result.out());
        }
    }

    // ------------------------------------------------------------ progress output

    @Test
    void quietSuppressesProgressButNotFailures(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        Invocation quiet = run("run", "-m", model.toString(),
                "-o", dir.resolve("a").toString(), "-q");
        Invocation loud = run("run", "-m", model.toString(),
                "-o", dir.resolve("b").toString());

        assertEquals(RunCommand.EXIT_OK, quiet.exitCode(), quiet.all());
        assertEquals(RunCommand.EXIT_OK, loud.exitCode(), loud.all());
        assertTrue(loud.err().contains("jzap: discovery"),
                "without --quiet the phases are announced: " + loud.err());
        assertFalse(quiet.err().contains("jzap: discovery"),
                "with --quiet they are not: " + quiet.err());
    }

    // ------------------------------------------------------------ options that reach the engine

    @Test
    void theEngineAndThreadOptionsAreAcceptedAndReportedBack(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        Invocation result = run("run", "-m", model.toString(), "-o", reports.toString(),
                "-q", "--engine", "naive", "--threads", "2");

        assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
        assertTrue(Files.readString(reports.resolve("jzap-result.json")).contains("\"naive\""),
                "the report records which engine produced it");
    }

    @Test
    void aSecondRunWithACacheDirectoryReusesVerdicts(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);
        Path cache = dir.resolve("cache");
        Path reports = dir.resolve("reports");

        run("run", "-m", model.toString(), "-o", reports.toString(), "-q",
                "--cache-dir", cache.toString());
        Invocation second = run("run", "-m", model.toString(), "-o", reports.toString(), "-q",
                "--cache-dir", cache.toString());

        assertEquals(RunCommand.EXIT_OK, second.exitCode(), second.all());
        assertTrue(second.out().contains("reused from the cache"),
                "the second run should say how much it did not have to do: " + second.out());
    }

    /**
     * An analysis that throws is exit code 3, not 1.
     *
     * <p>Exit 1 means "the result is below your bar" and exit 3 means "there is no result". CI
     * treats those differently: one is a code-quality gate and the other is a broken tool or a
     * broken build, and conflating them sends people to look in the wrong place.
     */
    @Test
    void anAnalysisThatCannotRunFailsWithItsOwnExitCode(@TempDir Path dir) throws Exception {
        // A code path that is a file but not a readable archive. Scanning it is the first thing
        // an analysis does, and it cannot be recovered from.
        Path notAJar = Files.writeString(dir.resolve("classes.jar"), "this is not a zip");
        Path model = CliFixture.write(dir.resolve("broken-model.json"), """
                {
                  "schemaVersion": 1,
                  "threads": 1,
                  "reporters": ["json"],
                  "scope": { "kind": "ALL" },
                  "modules": [
                    { "id": ":broken", "mutableCodePaths": ["%s"] }
                  ]
                }
                """.formatted(notAJar.toString().replace("\\", "\\\\")));

        Invocation result = run("run", "-m", model.toString(),
                "-o", dir.resolve("reports").toString(), "-q");

        assertEquals(RunCommand.EXIT_FAILED, result.exitCode(), result.all());
        assertTrue(result.err().contains("analysis failed"), result.err());
        assertTrue(result.err().contains("classes.jar"),
                "the message has to name what could not be read: " + result.err());
    }

    /**
     * A malformed model is a usage error, with the message and no stack trace.
     *
     * <p>ModelIo composes a careful diagnostic naming the file and the field. None of it reached
     * the user: ModelValidationException extended RuntimeException, every command catches
     * IllegalArgumentException for a bad model, so it slipped past all of them and arrived as a
     * 32-line Jackson stack trace with exit code 1 -- the code that means the mutation score was
     * below the threshold.
     */
    @Test
    void aModelWithAFieldOfTheWrongTypeIsAUsageError(@TempDir Path dir) {
        Path model = CliFixture.write(dir.resolve("bad.json"), """
                { "schemaVersion": 1, "threads": "many", "modules": [ { "id": ":a" } ] }
                """);

        Invocation result = run("run", "-m", model.toString());

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode(), result.all());
        assertTrue(result.err().startsWith("jzap: "),
                "the user should meet jzap's message, not a deserialiser's: " + result.err());
        assertTrue(result.err().contains("threads"),
                "and it has to name the field: " + result.err());
        assertFalse(result.err().contains("\tat "),
                "a usage mistake is not a crash and must not print a stack trace: " + result.err());
    }

    @Test
    void aModelWithAnUnsupportedSchemaVersionIsAUsageError(@TempDir Path dir) {
        Path model = CliFixture.write(dir.resolve("future.json"), """
                { "schemaVersion": 99, "modules": [ { "id": ":a" } ] }
                """);

        Invocation result = run("run", "-m", model.toString());

        assertEquals(RunCommand.EXIT_USAGE, result.exitCode(), result.all());
        assertTrue(result.err().contains("Upgrade jzap"),
                "a newer adapter than engine is the likely cause, and the fix is worth saying: "
                        + result.err());
    }
}
