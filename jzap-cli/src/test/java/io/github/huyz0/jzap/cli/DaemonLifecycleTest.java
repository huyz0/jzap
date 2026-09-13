package io.github.huyz0.jzap.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting and stopping a real daemon process.
 *
 * <p>Separate from DaemonServerTest, which serves one on a thread to exercise the protocol. This
 * forks the process the way a user's build does, so it covers the part that only exists in the
 * fork: the command line it is started with, waiting until it answers, and the {@code daemon}
 * subcommand's own reporting.
 */
class DaemonLifecycleTest {

    private Path modelFile;

    @AfterEach
    void stopWhateverWasStarted() throws Exception {
        if (modelFile != null) {
            Daemon.stop(modelFile);
            Files.deleteIfExists(Daemon.portFile(modelFile));
        }
    }

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

    @Test
    void aForkedDaemonComesUpAndAnswers(@TempDir Path dir) {
        modelFile = new CliFixture().writeModel(dir);

        assertTrue(Daemon.start(modelFile, List.of("-D" + RunCommand.IN_DAEMON + "=true")),
                "start waits until it answers, so a false here means it never came up");
        assertTrue(Daemon.ping(modelFile).isPresent());
        assertTrue(Files.isRegularFile(Daemon.portFile(modelFile)));
    }

    @Test
    void aForkedDaemonRunsAnAnalysisForTheCaller(@TempDir Path dir) throws Exception {
        modelFile = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");
        assertTrue(Daemon.start(modelFile, List.of("-D" + RunCommand.IN_DAEMON + "=true")));

        var response = Daemon.run(modelFile, List.of("run",
                "-m", modelFile.toAbsolutePath().toString(),
                "-o", reports.toAbsolutePath().toString(), "-q"));

        assertTrue(response.isPresent());
        assertEquals(RunCommand.EXIT_OK, response.get().exitCode(), response.get().output());
        assertTrue(Files.isRegularFile(reports.resolve("jzap-result.json")),
                "a separate process still has to write the caller's reports");
    }

    @Test
    void theSubcommandReportsWhatItStartedAndStopped(@TempDir Path dir) {
        modelFile = new CliFixture().writeModel(dir);

        Invocation started = run("daemon", "-m", modelFile.toString());
        assertEquals(RunCommand.EXIT_OK, started.exitCode(), started.all());
        assertTrue(started.out().contains("daemon started for"), started.out());

        Invocation status = run("daemon", "-m", modelFile.toString(), "--status");
        assertTrue(status.out().contains("daemon running for"), status.out());
        assertTrue(status.out().contains("port file:"),
                "somebody debugging a stuck daemon needs to know where to look: " + status.out());

        Invocation stopped = run("daemon", "-m", modelFile.toString(), "--stop");
        assertEquals(RunCommand.EXIT_OK, stopped.exitCode(), stopped.all());
        assertTrue(stopped.out().contains("daemon stopped"), stopped.out());

        // It answers the stop request and then exits, so it can still be answering for a
        // moment afterwards.
        assertTrue(awaitGone(), "a stopped daemon must really go away");
    }

    /** True once nothing answers for this model, within a few seconds. */
    private boolean awaitGone() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Daemon.ping(modelFile).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    void startingTwiceReusesTheOneThatIsAlreadyThere(@TempDir Path dir) throws Exception {
        modelFile = new CliFixture().writeModel(dir);
        assertTrue(Daemon.start(modelFile, List.of("-D" + RunCommand.IN_DAEMON + "=true")));
        String firstPort = Files.readString(Daemon.portFile(modelFile));

        Invocation second = run("daemon", "-m", modelFile.toString());

        assertEquals(RunCommand.EXIT_OK, second.exitCode(), second.all());
        assertEquals(firstPort, Files.readString(Daemon.portFile(modelFile)),
                "one daemon per project: a second start must not orphan the first");
    }
}
