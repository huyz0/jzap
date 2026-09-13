package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.wire.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resident daemon, served in this JVM rather than forked.
 *
 * <p>The end-to-end DaemonTest starts a real daemon through the installed binary, which is what
 * proves the thing users run works. It cannot see inside it: the protocol, the idle bookkeeping
 * and the recovery from a stale port file are all in the forked process. Those are what this
 * covers.
 *
 * <p>Each test uses its own model file, and the port file is keyed by that path, so nothing here
 * can find or disturb a daemon a developer happens to be running.
 */
class DaemonServerTest {

    /** A daemon serving on a thread, shut down by closing it. */
    private static final class ServedDaemon implements AutoCloseable {
        private final Path modelFile;
        private final Thread thread;
        private final ByteArrayOutputStream log = new ByteArrayOutputStream();

        ServedDaemon(Path modelFile) throws Exception {
            this.modelFile = modelFile;
            PrintStream sink = new PrintStream(log, true, StandardCharsets.UTF_8);
            this.thread = new Thread(() -> {
                try {
                    Daemon.serve(modelFile, sink);
                } catch (Exception e) {
                    sink.println("serve failed: " + e);
                }
            }, "daemon-under-test");
            thread.setDaemon(true);
            thread.start();
            awaitReady();
        }

        private void awaitReady() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                if (Daemon.ping(modelFile).isPresent()) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new IllegalStateException("the daemon never answered. Log:\n" + log());
        }

        String log() {
            return log.toString(StandardCharsets.UTF_8);
        }

        int port() throws Exception {
            return Integer.parseInt(
                    Files.readString(Daemon.portFile(modelFile), StandardCharsets.UTF_8).trim());
        }

        @Override
        public void close() throws Exception {
            Daemon.stop(modelFile);
            thread.join(TimeUnit.SECONDS.toMillis(20));
            Files.deleteIfExists(Daemon.portFile(modelFile));
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    void thereIsNoDaemonUntilOneIsServed(@TempDir Path dir) {
        Path model = new CliFixture().writeModel(dir);

        assertEquals(Optional.empty(), Daemon.ping(model),
                "no port file means no daemon, and that must not be an error");
    }

    @Test
    void aServedDaemonAnswersAPingAndWritesItsPortFile(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);

        try (ServedDaemon daemon = new ServedDaemon(model)) {
            Optional<Daemon.Response> response = Daemon.ping(model);

            assertTrue(response.isPresent(), daemon.log());
            assertEquals(0, response.get().exitCode());
            assertEquals("alive", response.get().output());
            assertTrue(Files.isRegularFile(Daemon.portFile(model)),
                    "the port file is how the next invocation finds it");
            assertTrue(daemon.port() > 0);
        }
    }

    @Test
    void stoppingItRemovesThePortFileSoNothingFindsItAgain(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        ServedDaemon daemon = new ServedDaemon(model);

        Optional<Daemon.Response> stopped = Daemon.stop(model);

        assertTrue(stopped.isPresent());
        assertEquals("stopped", stopped.get().output());
        daemon.close();
        assertFalse(Files.exists(Daemon.portFile(model)),
                "a daemon that has gone must not leave a port file behind");
        assertEquals(Optional.empty(), Daemon.ping(model));
    }

    @Test
    void thePortFileIsPerModelSoTwoProjectsDoNotShareADaemon(@TempDir Path dir) {
        Path one = new CliFixture().writeModel(dir.resolve("one"));
        Path two = new CliFixture().writeModel(dir.resolve("two"));

        assertFalse(Daemon.portFile(one).equals(Daemon.portFile(two)),
                "one daemon per project, keyed by the model it serves");
    }

    // ------------------------------------------------------------ running analyses

    @Test
    void itRunsAnAnalysisAndReturnsWhatTheCommandPrinted(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path reports = dir.resolve("reports");

        try (ServedDaemon daemon = new ServedDaemon(model)) {
            Optional<Daemon.Response> response = Daemon.run(model, List.of(
                    "run", "-m", model.toAbsolutePath().toString(),
                    "-o", reports.toAbsolutePath().toString(), "-q"));

            assertTrue(response.isPresent(), daemon.log());
            assertEquals(RunCommand.EXIT_OK, response.get().exitCode(),
                    response.get().output() + daemon.log());
            assertTrue(Files.isRegularFile(reports.resolve("jzap-result.json")),
                    "the daemon runs the same command, so it writes the same reports");
            assertTrue(response.get().output().contains("Reports written to"),
                    "the caller has to see exactly what a direct run would have printed: "
                            + response.get().output());
        }
    }

    @Test
    void aCommandThatFailsReturnsItsExitCodeRatherThanBreakingTheDaemon(@TempDir Path dir)
            throws Exception {
        Path model = new CliFixture().writeModel(dir);

        try (ServedDaemon daemon = new ServedDaemon(model)) {
            Optional<Daemon.Response> failed = Daemon.run(model, List.of(
                    "run", "-m", dir.resolve("absent.json").toAbsolutePath().toString()));

            assertTrue(failed.isPresent(), daemon.log());
            assertEquals(RunCommand.EXIT_USAGE, failed.get().exitCode());
            assertTrue(failed.get().output().contains("no project model at"),
                    failed.get().output());

            assertTrue(Daemon.ping(model).isPresent(),
                    "a failed request must leave the daemon serving the next one");
        }
    }

    @Test
    void itServesOneRequestAfterAnother(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);

        try (ServedDaemon daemon = new ServedDaemon(model)) {
            for (int i = 0; i < 3; i++) {
                assertTrue(Daemon.ping(model).isPresent(),
                        "request " + i + " should be served. " + daemon.log());
            }
        }
    }

    // ------------------------------------------------------------ protocol edges

    @Test
    void anUnknownRequestIsRejectedWithoutStoppingTheDaemon(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);

        try (ServedDaemon daemon = new ServedDaemon(model)) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), daemon.port());
                 Channel channel = new Channel(socket)) {
                channel.readTimeout(0);
                channel.writeByte((byte) 99);
                channel.flush();

                assertEquals(RunCommand.EXIT_USAGE, channel.readInt(),
                        "an unrecognised request is a usage error");
                assertTrue(channel.readString().contains("unknown request 99"));
            }

            assertTrue(Daemon.ping(model).isPresent(),
                    "and it must not take the daemon down with it");
        }
    }

    // ------------------------------------------------------------ stale state

    @Test
    void aStalePortFileIsCleanedUpSoTheNextRunStartsFresh(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path portFile = Daemon.portFile(model);
        Files.createDirectories(portFile.getParent());
        // A port nothing is listening on, which is what a daemon killed by a reboot leaves.
        Files.writeString(portFile, "1", StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), Daemon.ping(model));
        assertFalse(Files.exists(portFile),
                "leaving it would make every later invocation fail the same way");
    }

    @Test
    void aPortFileThatIsNotANumberIsIgnored(@TempDir Path dir) throws Exception {
        Path model = new CliFixture().writeModel(dir);
        Path portFile = Daemon.portFile(model);
        Files.createDirectories(portFile.getParent());
        Files.writeString(portFile, "not a port", StandardCharsets.UTF_8);
        try {
            assertEquals(Optional.empty(), Daemon.ping(model));
        } finally {
            Files.deleteIfExists(portFile);
        }
    }
}
