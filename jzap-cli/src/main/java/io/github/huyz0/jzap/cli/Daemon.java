package io.github.huyz0.jzap.cli;

import io.github.huyz0.jzap.wire.Channel;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A resident jzap that runs analyses in a JVM that is already warm.
 *
 * <p>What it saves is the fixed cost of starting the tool: JVM startup, class loading, and the JIT
 * warmup of the engine itself. On a fully cached run that is most of the wall clock -- the analysis
 * proper is a cache read.
 *
 * <p>Deliberately narrow. It does not keep analysis JVMs alive between invocations, which would
 * save more and would mean holding the code under test loaded in a process that outlives the build
 * that produced it. Bounded reuse inside a single run is already the subject of a soundness test;
 * reuse across runs would need its own, and the measured saving does not yet justify the hazard.
 *
 * <p>Each daemon serves one project, keyed by the absolute path of its project model, and exits on
 * its own after an idle period so a forgotten daemon is a temporary condition rather than a
 * permanent one.
 */
final class Daemon {

    private static final byte REQUEST_RUN = 1;
    private static final byte REQUEST_STOP = 2;
    private static final byte REQUEST_PING = 3;

    private static final long IDLE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private Daemon() {
    }

    /** Where a daemon for this model records the port it is listening on. */
    static Path portFile(Path modelFile) {
        String key = Integer.toHexString(modelFile.toAbsolutePath().toString().hashCode());
        return Path.of(System.getProperty("user.home"), ".jzap", "daemon-" + key + ".port");
    }

    // ------------------------------------------------------------------ server

    /** Serves requests until stopped or idle. Returns when the daemon should exit. */
    static void serve(Path modelFile, PrintStream log) throws IOException {
        Path portFile = portFile(modelFile);
        Files.createDirectories(portFile.getParent());
        int ourPort = -1;

        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            ourPort = server.getLocalPort();
            publishPort(portFile, ourPort);
            log.println("jzap daemon listening on port " + ourPort
                    + " for " + modelFile.toAbsolutePath());
            AtomicLong lastUsed = new AtomicLong(System.currentTimeMillis());
            server.setSoTimeout(60_000);

            while (true) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (java.net.SocketTimeoutException e) {
                    if (System.currentTimeMillis() - lastUsed.get() > IDLE_TIMEOUT_MILLIS) {
                        log.println("jzap daemon exiting after being idle");
                        break;
                    }
                    continue;
                }
                lastUsed.set(System.currentTimeMillis());
                try (Channel channel = new Channel(socket)) {
                    if (!handle(channel, log)) {
                        break;
                    }
                } catch (RuntimeException e) {
                    log.println("jzap daemon: request failed: " + e);
                }
                lastUsed.set(System.currentTimeMillis());
            }
        } finally {
            unpublishPort(portFile, ourPort);
        }
    }

    /**
     * Records the port, atomically.
     *
     * <p>Written to a temporary file and moved into place so a client never reads a half-written
     * one. A partial read parses as no daemon, which starts a second daemon for a project that
     * already has one.
     */
    private static void publishPort(Path portFile, int port) throws IOException {
        Path temporary = portFile.resolveSibling(portFile.getFileName() + ".tmp" + ProcessHandle.current().pid());
        Files.writeString(temporary, Integer.toString(port), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, portFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, portFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes the port file, but only while it still names this daemon.
     *
     * <p>Two daemons can exist for one project, because starting one is not serialised: the second
     * overwrites the first's port file and takes over. The first then has no clients and leaves on
     * its idle timeout half an hour later -- and deleting the file unconditionally at that point
     * would deregister the daemon that is actually serving, so the next invocation starts a third.
     */
    private static void unpublishPort(Path portFile, int port) {
        try {
            String recorded = Files.readString(portFile, StandardCharsets.UTF_8).trim();
            if (recorded.equals(Integer.toString(port))) {
                Files.deleteIfExists(portFile);
            }
        } catch (IOException | RuntimeException ignored) {
            // Gone already, or unreadable. Either way there is nothing of ours left to remove.
        }
    }

    /** @return false when the daemon should stop */
    private static boolean handle(Channel channel, PrintStream log) throws IOException {
        int request = channel.readByte();
        if (request == REQUEST_STOP) {
            respond(channel, RunCommand.EXIT_OK, "stopped");
            log.println("jzap daemon stopping on request");
            return false;
        }
        if (request == REQUEST_PING) {
            respond(channel, RunCommand.EXIT_OK, "alive");
            return true;
        }
        if (request != REQUEST_RUN) {
            respond(channel, RunCommand.EXIT_USAGE, "unknown request " + request);
            return true;
        }

        int count = channel.readInt();
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            args.add(channel.readString());
        }

        // The command writes to standard out and standard error; both are captured and sent back
        // so the caller sees exactly what a direct run would have printed.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PrintStream outWas = System.out;
        PrintStream errWas = System.err;
        int exit;
        try {
            System.setOut(sink);
            System.setErr(sink);
            exit = new CommandLine(new JzapCommand()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(outWas);
            System.setErr(errWas);
        }
        respond(channel, exit, captured.toString(StandardCharsets.UTF_8));
        return true;
    }

    private static void respond(Channel channel, int exitCode, String output) {
        channel.writeInt(exitCode);
        channel.writeString(output);
        channel.flush();
    }

    // ------------------------------------------------------------------ client

    /** @return the daemon's response, or empty when no daemon is running for this model */
    static Optional<Response> send(Path modelFile, byte request, List<String> args) {
        Path portFile = portFile(modelFile);
        if (!Files.isRegularFile(portFile)) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(Files.readString(portFile, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
             Channel channel = new Channel(socket)) {
            channel.readTimeout(0);
            channel.writeByte(request);
            if (request == REQUEST_RUN) {
                channel.writeInt(args.size());
                for (String arg : args) {
                    channel.writeString(arg);
                }
            }
            channel.flush();
            return Optional.of(new Response(channel.readInt(), channel.readString()));
        } catch (IOException e) {
            // A stale port file from a daemon that is gone. Cleaning it up here means the next
            // invocation starts a fresh one instead of failing the same way again.
            try {
                Files.deleteIfExists(portFile);
            } catch (IOException ignored) {
                // nothing useful to do; the next run will try again
            }
            return Optional.empty();
        }
    }

    static Optional<Response> run(Path modelFile, List<String> args) {
        return send(modelFile, REQUEST_RUN, args);
    }

    static Optional<Response> stop(Path modelFile) {
        return send(modelFile, REQUEST_STOP, List.of());
    }

    static Optional<Response> ping(Path modelFile) {
        return send(modelFile, REQUEST_PING, List.of());
    }

    /** Starts a daemon and waits until it is answering. */
    static boolean start(Path modelFile, List<String> jvmArgs) {
        try {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmArgs);
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("-D" + RunCommand.IN_DAEMON + "=true");
            command.add("io.github.huyz0.jzap.cli.Main");
            command.add("daemon");
            command.add("--serve");
            command.add("-m");
            command.add(modelFile.toAbsolutePath().toString());

            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (ping(modelFile).isPresent()) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the jzap daemon", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    record Response(int exitCode, String output) {
    }
}
