package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.wire.Channel;
import io.github.huyz0.jzap.wire.Wire;
import io.github.huyz0.jzap.wire.WireException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A forked analysis JVM, from the controller's side.
 *
 * <p>One process serves many mutants, because process startup is expensive relative to a
 * single mutant. It is nonetheless recycled periodically: leftover static state from an
 * earlier mutant is the classic way a mutation testing tool reports a verdict that cannot be
 * reproduced, so bounding how long state can accumulate is a correctness measure, not a
 * tidiness one.
 */
final class MinionProcess implements AutoCloseable {

    /** How long to wait for a minion to connect back before giving up on it. */
    private static final int CONNECT_TIMEOUT_MILLIS = 60_000;

    /** Discovering a large suite is the slowest thing a minion is ever asked to do. */
    private static final int DISCOVERY_TIMEOUT_MILLIS = 300_000;

    /** Installing classes: bounded work, but proportional to how many are in scope. */
    private static final int INSTALL_TIMEOUT_MILLIS = 120_000;

    /** A bookkeeping round trip that does no user work, so it should answer immediately. */
    private static final int CONTROL_TIMEOUT_MILLIS = 30_000;

    /** How long a minion gets to exit on request before it is killed. */
    private static final int EXIT_GRACE_SECONDS = 5;

    /** Thrown when a mutant hangs the analysis JVM. The process must then be destroyed. */
    public static final class HungException extends RuntimeException {
        public HungException(String message) {
            super(message);
        }
    }

    /**
     * @param loopIterations back edges executed by this test with no mutant applied, which is
     *                       what a mutant's iteration limit is derived from
     */
    public record TestCoverage(boolean passed, String failureMessage, long durationMillis,
                               long loopIterations, int[] probeIds) {
    }

    public record MutantOutcome(byte code, String failingTest, String failureMessage,
                                int testsRun) {
    }

    private final Process process;
    private final Channel channel;
    private final StringBuilder output = new StringBuilder();
    private final Thread drain;
    private volatile boolean dead;

    private MinionProcess(Process process, Channel channel) {
        this.process = process;
        this.channel = channel;
        this.drain = new Thread(() -> drainOutput(process.getInputStream()), "jzap-minion-output");
        this.drain.setDaemon(true);
        this.drain.start();
    }

    public static MinionProcess start(ModuleModel module, RuntimeJars.Jars jars) {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            List<String> command = buildCommand(module, jars, server.getLocalPort());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            // From here on the process is running, and every failure path has to kill it: if this
            // method throws, nothing is left holding a reference to stop it with, and an abandoned
            // analysis JVM keeps a whole test classpath loaded until the build ends.
            try {
                return new MinionProcess(process, new Channel(server.accept()));
            } catch (SocketTimeoutException e) {
                process.destroyForcibly();
                throw new WireException("the analysis JVM did not connect within "
                        + CONNECT_TIMEOUT_MILLIS / 1000 + "s. Command was:\n  "
                        + String.join(" ", command), e);
            } catch (IOException | RuntimeException e) {
                process.destroyForcibly();
                throw new WireException("cannot open a channel to the analysis JVM. Command was:"
                        + "\n  " + String.join(" ", command), e);
            }
        } catch (IOException e) {
            throw new WireException("cannot start an analysis JVM", e);
        }
    }

    private static List<String> buildCommand(ModuleModel module, RuntimeJars.Jars jars, int port) {
        String javaHome = module.javaHome() != null && !module.javaHome().isBlank()
                ? module.javaHome()
                : System.getProperty("java.home");
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", "java").toString());
        // Always: the agent is what installs mutants and collects probes, so a minion without it
        // cannot do either job. The minion refuses to run tests at all if it is missing, rather
        // than reporting every mutant as having survived.
        command.add("-javaagent:" + jars.agent());
        command.addAll(module.jvmArgs());
        command.add("-cp");
        List<String> classpath = new ArrayList<>();
        classpath.add(jars.minion().toString());
        classpath.add(jars.wire().toString());
        classpath.add(jars.agent().toString());
        classpath.addAll(module.testClasspath());
        command.add(String.join(File.pathSeparator, classpath));
        command.add("io.github.huyz0.jzap.minion.Minion");
        command.add(Integer.toString(port));
        return command;
    }

    public void initCoverage(int probeCount, List<ClassBytes> instrumented) {
        channel.readTimeout(INSTALL_TIMEOUT_MILLIS);
        channel.writeByte(Wire.CMD_INIT_COVERAGE);
        channel.writeInt(probeCount);
        channel.writeInt(instrumented.size());
        for (ClassBytes c : instrumented) {
            channel.writeString(c.binaryName());
            channel.writeBytes(c.bytes());
        }
        channel.flush();
        expectOk("initialising coverage");
    }

    public List<String> listTests(List<String> testClassPaths) {
        channel.readTimeout(DISCOVERY_TIMEOUT_MILLIS);
        sendClassPaths(Wire.CMD_LIST_TESTS, testClassPaths);
        expectOk("discovering tests");
        try {
            int n = channel.readInt();
            List<String> tests = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                tests.add(channel.readString());
            }
            return tests;
        } catch (IOException e) {
            throw new WireException("truncated response while discovering tests" + outputSuffix(), e);
        }
    }

    /**
     * Makes the analysis JVM ready to run tests, without discovering them.
     *
     * <p>Discovery is what the coverage phase is for. Repeating it in every analysis JVM cost a
     * fifth of a second each and produced a list nothing read.
     */
    public void prepareTests(List<String> testClassPaths) {
        channel.readTimeout(INSTALL_TIMEOUT_MILLIS);
        sendClassPaths(Wire.CMD_PREPARE_TESTS, testClassPaths);
        expectOk("preparing the test harness");
    }

    private void sendClassPaths(byte command, List<String> testClassPaths) {
        channel.writeByte(command);
        channel.writeInt(testClassPaths.size());
        for (String root : testClassPaths) {
            channel.writeString(root);
        }
        channel.flush();
    }

    public TestCoverage runTestForCoverage(String testId, int timeoutMillis) {
        channel.readTimeout(timeoutMillis);
        channel.writeByte(Wire.CMD_RUN_TEST_COVERAGE);
        channel.writeString(testId);
        channel.flush();
        expectOk("running " + testId);
        try {
            boolean passed = channel.readBool();
            String failureMessage = channel.readString();
            long millis = channel.readLong();
            long ticks = channel.readLong();
            int n = channel.readInt();
            int[] probes = new int[n];
            for (int i = 0; i < n; i++) {
                probes[i] = channel.readInt();
            }
            return new TestCoverage(passed, failureMessage.isEmpty() ? null : failureMessage,
                    millis, ticks, probes);
        } catch (SocketTimeoutException e) {
            throw new HungException("test " + testId + " did not finish within " + timeoutMillis + "ms");
        } catch (IOException e) {
            throw new WireException("truncated response while running " + testId + outputSuffix(), e);
        }
    }

    public void setOverride(String className, byte[] bytes) {
        channel.readTimeout(CONTROL_TIMEOUT_MILLIS);
        channel.writeByte(Wire.CMD_SET_OVERRIDE);
        channel.writeString(className);
        channel.writeBytes(bytes);
        channel.flush();
        expectOk("installing a mutant in " + className);
    }

    public void clearOverrides() {
        channel.readTimeout(CONTROL_TIMEOUT_MILLIS);
        channel.writeByte(Wire.CMD_CLEAR_OVERRIDES);
        channel.flush();
        expectOk("clearing mutants");
    }

    /**
     * @param iterationLimit loop iterations past which the mutant is declared runaway. Derived
     *                       from what the unmutated code needed, so the verdict does not depend
     *                       on how fast the machine is.
     * @param timeoutMillis  wall-clock backstop, for the cases the guard cannot see: a mutant
     *                       that blocks rather than loops, or code that catches Throwable
     * @param mutantIndex    schemata mutant to activate for this run, or
     *                       {@link io.github.huyz0.jzap.agent.MutantSwitch#NONE} when the mutant was installed
     *                       by redefinition instead. Carried here so one round trip does what three
     *                       used to.
     */
    public MutantOutcome runTests(List<String> testIds, long iterationLimit, int timeoutMillis,
                                  int mutantIndex) {
        channel.readTimeout(timeoutMillis);
        channel.writeByte(Wire.CMD_RUN_TESTS);
        channel.writeInt(testIds.size());
        channel.writeLong(iterationLimit);
        channel.writeInt(mutantIndex);
        for (String id : testIds) {
            channel.writeString(id);
        }
        channel.flush();
        try {
            int response = channel.readByte();
            if (response == Wire.RESP_ERROR) {
                throw new WireException("analysis JVM reported: " + channel.readString());
            }
            byte code = (byte) channel.readByte();
            String failing = channel.readString();
            String failureMessage = channel.readString();
            int testsRun = channel.readInt();
            return new MutantOutcome(code, failing.isEmpty() ? null : failing,
                    failureMessage.isEmpty() ? null : failureMessage, testsRun);
        } catch (SocketTimeoutException e) {
            throw new HungException("tests did not finish within " + timeoutMillis
                    + "ms, which is how an infinite loop presents itself");
        } catch (IOException e) {
            throw new WireException("truncated response while running tests" + outputSuffix(), e);
        }
    }

    private void expectOk(String what) {
        try {
            int response = channel.readByte();
            if (response == Wire.RESP_OK) {
                return;
            }
            if (response == Wire.RESP_ERROR) {
                throw new WireException("analysis JVM failed while " + what + ": " + channel.readString());
            }
            throw new WireException("unexpected response " + response + " while " + what);
        } catch (SocketTimeoutException e) {
            throw new HungException("no response while " + what);
        } catch (IOException e) {
            throw new WireException("analysis JVM went away while " + what + outputSuffix(), e);
        }
    }

    private void drainOutput(InputStream in) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (output) {
                    if (output.length() < 64_000) {
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException ignored) {
            // the process ended; whatever it had already said is kept
        }
    }

    /** Everything the analysis JVM printed, for diagnostics when something goes wrong. */
    public String output() {
        synchronized (output) {
            return output.toString();
        }
    }

    private String outputSuffix() {
        String out = output();
        return out.isBlank() ? "" : "\nAnalysis JVM output:\n" + out;
    }

    public boolean isAlive() {
        return !dead && process.isAlive();
    }

    /** Asks the minion to exit, then makes sure it has. */
    @Override
    public void close() {
        try {
            channel.writeByte(Wire.CMD_EXIT);
            channel.flush();
            process.waitFor(EXIT_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // a minion that will not exit cleanly is destroyed below like any other
        }
        destroy();
    }

    /** Kills the process immediately. The only reliable way to stop a mutant that hangs. */
    public void destroy() {
        dead = true;
        channel.close();
        process.destroyForcibly();
        drain.interrupt();
    }
}
