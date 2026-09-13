package io.github.huyz0.jzap.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code run --daemon} handing the work to a resident jzap.
 *
 * <p>The daemon runs the very same command with {@code --daemon} dropped, so there is only one
 * implementation of a run and behaviour cannot drift between the two paths. What can drift is the
 * forwarding: an option the caller gave that is not passed on would be silently ignored, and the
 * user would see a run that quietly did something else.
 */
class DaemonForwardingTest {

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

    /** A daemon serving on a thread of this JVM, so nothing has to be forked. */
    private static final class Served implements AutoCloseable {
        private final Path modelFile;
        private final Thread thread;

        Served(Path modelFile) throws Exception {
            this.modelFile = modelFile;
            this.thread = new Thread(() -> {
                try {
                    Daemon.serve(modelFile, new PrintStream(new ByteArrayOutputStream()));
                } catch (Exception ignored) {
                    // the assertions below report the failure
                }
            }, "daemon-under-test");
            thread.setDaemon(true);
            thread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && Daemon.ping(modelFile).isEmpty()) {
                Thread.sleep(20);
            }
        }

        @Override
        public void close() throws Exception {
            Daemon.stop(modelFile);
            thread.join(TimeUnit.SECONDS.toMillis(20));
            Files.deleteIfExists(Daemon.portFile(modelFile));
        }
    }

    @Test
    void aRunIsHandedToADaemonThatIsAlreadyServing(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (Served served = new Served(model)) {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "-q");

            assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
            assertTrue(Files.isRegularFile(reports.resolve("jzap-result.json")),
                    "the daemon writes the reports the caller asked for");
        }
    }

    @Test
    void thresholdAndEngineChoicesAreForwarded(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (Served served = new Served(model)) {
            Invocation failed = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "-q", "--threshold", "100");

            assertEquals(RunCommand.EXIT_THRESHOLD, failed.exitCode(),
                    "a threshold that is not forwarded would turn a failing build green: "
                            + failed.all());
            assertTrue(failed.all().contains("below the threshold"), failed.all());
        }
    }

    @Test
    void theCacheDirectoryIsForwardedSoTheSecondRunIsCheap(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path cache = dir.resolve("cache");

        try (Served served = new Served(model)) {
            run("run", "--daemon", "-m", model.toString(),
                    "-o", dir.resolve("a").toString(), "-q", "--cache-dir", cache.toString());

            assertTrue(Files.isRegularFile(cache.resolve("jzap-cache.txt")),
                    "a cache directory that was not forwarded would leave nothing behind");

            Invocation second = run("run", "--daemon", "-m", model.toString(),
                    "-o", dir.resolve("b").toString(), "-q", "--cache-dir", cache.toString());

            assertTrue(second.all().contains("reused from the cache"), second.all());
        }
    }

    @Test
    void anEngineChoiceIsForwarded(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (Served served = new Served(model)) {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "-q", "--engine", "naive", "--threads", "2");

            assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
            assertTrue(Files.readString(reports.resolve("jzap-result.json")).contains("\"naive\""),
                    "the report records the engine, so a dropped --engine would show here");
        }
    }

    @Test
    void diffRefsAreForwarded(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);

        Path reports = dir.resolve("reports");
        try (Served served = new Served(model)) {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "-q", "-r", "json",
                    "--from", "HEAD", "--to", "-Local-");

            assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
            String report = Files.readString(reports.resolve("jzap-result.json"));
            assertTrue(report.contains("changed lines between HEAD"),
                    "refs that were not forwarded would silently analyse everything, and the "
                            + "report would say so: " + report);
        }
    }

    /**
     * A dry run stays a dry run.
     *
     * <p>This is the shape of bug the forwarding used to have: the option was dropped, the daemon
     * performed a real analysis, reports were written, and nothing said that what the user asked
     * for had been ignored.
     */
    @Test
    void dryRunIsForwardedRatherThanQuietlyBecomingARealRun(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (Served served = new Served(model)) {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "--dry-run");

            assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
            assertTrue(result.all().contains("Scope kind:"), result.all());
            assertFalse(Files.exists(reports.resolve("jzap-result.json")),
                    "a dry run must not analyse anything, daemon or not");
        }
    }

    @Test
    void failOnSurvivorsIsForwarded(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);

        try (Served served = new Served(model)) {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", dir.resolve("reports").toString(), "-q", "--fail-on-survivors");

            assertEquals(RunCommand.EXIT_THRESHOLD, result.exitCode(),
                    "dropping this turns a failing build green, which is the worst kind of bug "
                            + "for a verification tool: " + result.all());
        }
    }

    @Test
    void reporterSelectionIsForwarded(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (Served served = new Served(model)) {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "-q", "-r", "html");

            assertEquals(RunCommand.EXIT_OK, result.exitCode(), result.all());
            assertTrue(Files.isRegularFile(reports.resolve("index.html")),
                    "asking for html and getting the model's default reporters is not the same run");
            assertFalse(Files.exists(reports.resolve("jzap-result.json")),
                    "and only what was asked for should be written");
        }
    }

    @Test
    void scopeAndFilterFlagsAreForwarded(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (Served served = new Served(model)) {
            Invocation everything = run("run", "--daemon", "-m", model.toString(),
                    "-o", reports.toString(), "-q", "-r", "json");
            assertEquals(RunCommand.EXIT_OK, everything.exitCode(), everything.all());
            long all = countMutants(reports.resolve("jzap-result.json"));

            Path narrowed = dir.resolve("narrowed");
            Invocation onePerLine = run("run", "--daemon", "-m", model.toString(),
                    "-o", narrowed.toString(), "-q", "-r", "json", "--one-per-line");
            assertEquals(RunCommand.EXIT_OK, onePerLine.exitCode(), onePerLine.all());

            assertTrue(countMutants(narrowed.resolve("jzap-result.json")) < all,
                    "a filter flag that was dropped would analyse the same inventory twice");
        }
    }

    private static long countMutants(Path json) throws Exception {
        String text = Files.readString(json);
        return text.split("\"key\"", -1).length - 1L;
    }

    @Test
    void aRunInsideTheDaemonDoesNotTryToForwardAgain(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);
        System.setProperty(RunCommand.IN_DAEMON, "true");
        try {
            Invocation result = run("run", "--daemon", "-m", model.toString(),
                    "-o", dir.resolve("reports").toString(), "-q");

            assertEquals(RunCommand.EXIT_OK, result.exitCode(),
                    "without this guard the daemon would hand the work to itself forever: "
                            + result.all());
        } finally {
            System.clearProperty(RunCommand.IN_DAEMON);
        }
    }
}
