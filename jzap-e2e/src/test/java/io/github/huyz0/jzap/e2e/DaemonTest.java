package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.core.testing.Fixture;
import io.github.huyz0.jzap.model.ModelIo;
import io.github.huyz0.jzap.model.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resident jzap, exercised through the installed binary rather than in process.
 *
 * <p>What it saves is the tool's own startup: JVM, class loading, JIT warmup. On a cached run that
 * is most of the wall clock, because the analysis itself is a cache read. Testing it any other way
 * would not exercise the part that matters, which is a separate process surviving between
 * invocations.
 */
class DaemonTest {

    @TempDir
    Path work;

    private Path modelFile;

    private static String cli() {
        String path = System.getProperty("jzap.cli");
        if (path == null) {
            throw new IllegalStateException("jzap.cli is not set; run this through Gradle");
        }
        return path;
    }

    private Path model() throws IOException {
        if (modelFile == null) {
            modelFile = work.resolve("model.json");
            new ModelIo().write(modelFile, new Fixture().model(Scope.all()));
        }
        return modelFile;
    }

    private record Result(int exitCode, String output) {
    }

    private Result run(String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(cli()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stopDaemon() throws IOException {
        if (modelFile != null) {
            run("daemon", "-m", modelFile.toString(), "--stop");
        }
    }

    @Test
    void aRunThroughTheDaemonProducesTheSameOutput() throws IOException {
        Path direct = work.resolve("direct");
        Path viaDaemon = work.resolve("via-daemon");

        Result plain = run("run", "-m", model().toString(), "-o", direct.toString(), "-q");
        Result daemon = run("run", "-m", model().toString(), "-o", viaDaemon.toString(),
                "-q", "--daemon");

        assertEquals(plain.exitCode(), daemon.exitCode(), daemon.output());
        assertEquals(withoutTimings(Files.readString(direct.resolve("jzap-result.json"))),
                withoutTimings(Files.readString(viaDaemon.resolve("jzap-result.json"))),
                "a warm JVM must not change a single verdict");
    }

    private static String withoutTimings(String json) {
        return json.substring(0, json.indexOf("\"timings\""));
    }

    @Test
    void theDaemonStartsOnDemandAndReportsItself() throws IOException {
        assertTrue(run("daemon", "-m", model().toString(), "--status").output()
                .contains("no daemon running"));

        run("run", "-m", model().toString(), "-o", work.resolve("out").toString(),
                "-q", "--daemon");

        assertTrue(run("daemon", "-m", model().toString(), "--status").output()
                .contains("daemon running for"));
    }

    @Test
    void stoppingLeavesNoPortFileBehind() throws IOException {
        run("run", "-m", model().toString(), "-o", work.resolve("out").toString(),
                "-q", "--daemon");
        Path portFile = Path.of(System.getProperty("user.home"), ".jzap",
                "daemon-" + Integer.toHexString(model().toAbsolutePath().toString().hashCode())
                        + ".port");
        assertTrue(Files.exists(portFile), "expected a port file at " + portFile);

        run("daemon", "-m", model().toString(), "--stop");

        // A stale port file would make the next invocation try to reach a process that is gone.
        long deadline = System.currentTimeMillis() + 10_000;
        while (Files.exists(portFile) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(Files.exists(portFile), "the daemon should remove its port file when it stops");
    }

    @Test
    void aStalePortFileDoesNotBreakTheNextRun() throws IOException {
        Path portFile = Path.of(System.getProperty("user.home"), ".jzap",
                "daemon-" + Integer.toHexString(model().toAbsolutePath().toString().hashCode())
                        + ".port");
        Files.createDirectories(portFile.getParent());
        // A port nothing is listening on, which is what a killed daemon leaves behind.
        Files.writeString(portFile, "1");

        Result result = run("run", "-m", model().toString(), "-o", work.resolve("out").toString(),
                "-q", "--daemon");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.exists(work.resolve("out/jzap-result.json")));
    }

    @Test
    void theSecondRunThroughADaemonIsFasterThanStartingFresh() throws IOException {
        Path cache = work.resolve("cache");
        run("run", "-m", model().toString(), "-o", work.resolve("warm").toString(),
                "-q", "--cache-dir", cache.toString());

        long fresh = time(() -> run("run", "-m", model().toString(),
                "-o", work.resolve("warm").toString(), "-q", "--cache-dir", cache.toString()));
        run("run", "-m", model().toString(), "-o", work.resolve("warm").toString(),
                "-q", "--cache-dir", cache.toString(), "--daemon");
        long resident = time(() -> run("run", "-m", model().toString(),
                "-o", work.resolve("warm").toString(), "-q", "--cache-dir", cache.toString(),
                "--daemon"));

        // Asserted as a direction rather than a ratio: what is being checked is that the resident
        // path is actually being taken, not a particular speed on a particular machine.
        assertTrue(resident < fresh,
                "expected the resident run to be faster, got " + resident + "ms vs " + fresh + "ms");
    }

    private interface Runner {
        Result run() throws IOException;
    }

    private static long time(Runner runner) throws IOException {
        long start = System.nanoTime();
        runner.run();
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
