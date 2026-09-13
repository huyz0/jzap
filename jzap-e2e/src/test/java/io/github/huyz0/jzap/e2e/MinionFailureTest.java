package io.github.huyz0.jzap.e2e;

import io.github.huyz0.jzap.agent.MutantSwitch;
import io.github.huyz0.jzap.core.MinionProcess;
import io.github.huyz0.jzap.core.RuntimeJars;
import io.github.huyz0.jzap.model.ModuleModel;
import io.github.huyz0.jzap.wire.WireException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when an analysis JVM will not start, will not answer, or dies mid-conversation.
 *
 * <p>These are the paths a user meets when something about their build is wrong, and they are the
 * ones least likely to be exercised by a passing test suite. Each has to say enough to act on:
 * the whole point of forking a JVM per module is that the controller survives its death, so a
 * dead minion must produce a diagnosis rather than a stack trace from deep in the protocol.
 */
class MinionFailureTest {

    private static ModuleModel fixtureModule() {
        return new Fixture().model(io.github.huyz0.jzap.model.Scope.all()).modules().get(0);
    }

    private static ModuleModel withJavaHome(ModuleModel module, String javaHome) {
        return new ModuleModel(module.id(), module.mutableCodePaths(), module.sourceRoots(),
                module.testClassPaths(), module.testClasspath(), javaHome,
                module.jvmArgs(), module.kotlinVersion());
    }

    @Test
    void aJavaHomeThatHoldsNoJavaCannotStartAnything() {
        ModuleModel broken = withJavaHome(fixtureModule(), "/does/not/exist");

        WireException e = assertThrows(WireException.class,
                () -> MinionProcess.start(broken, RuntimeJars.discover()));

        assertTrue(e.getMessage().contains("cannot start an analysis JVM"), e.getMessage());
    }

    @Test
    void theModulesOwnJavaHomeIsUsedWhenItIsSet() {
        // Tests must run on the JVM the build chose, not on whatever is running jzap. Passing
        // the current one proves the branch is taken without needing a second JDK installed.
        ModuleModel explicit = withJavaHome(fixtureModule(), System.getProperty("java.home"));

        try (MinionProcess minion = MinionProcess.start(explicit, RuntimeJars.discover())) {
            minion.prepareTests(explicit.testClassPaths());
            assertTrue(minion.isAlive());
        }
    }

    @Test
    void aModulesJvmArgsReachTheAnalysisJvm() {
        ModuleModel module = fixtureModule();
        ModuleModel withArgs = new ModuleModel(module.id(), module.mutableCodePaths(),
                module.sourceRoots(), module.testClassPaths(), module.testClasspath(), null,
                List.of("-Xmx256m", "-Djzap.test.marker=set"), module.kotlinVersion());

        try (MinionProcess minion = MinionProcess.start(withArgs, RuntimeJars.discover())) {
            minion.prepareTests(withArgs.testClassPaths());
            assertTrue(minion.isAlive(),
                    "the arguments have to be accepted by the JVM, not just appended");
        }
    }

    @Test
    void aMinionThatWasKilledIsNoLongerAlive() {
        MinionProcess minion = MinionProcess.start(fixtureModule(), RuntimeJars.discover());
        assertTrue(minion.isAlive());

        minion.destroy();

        assertFalse(minion.isAlive(),
                "the worker asks this before reusing one, so a killed minion must say so");
    }

    @Test
    void talkingToAKilledMinionIsDiagnosedRatherThanHanging() {
        MinionProcess minion = MinionProcess.start(fixtureModule(), RuntimeJars.discover());
        minion.destroy();

        WireException e = assertThrows(WireException.class,
                () -> minion.prepareTests(fixtureModule().testClassPaths()));

        assertFalse(e.getMessage().isBlank(), "a dead minion has to produce a diagnosis");
    }

    @Test
    void closingAKilledMinionIsHarmless() {
        MinionProcess minion = MinionProcess.start(fixtureModule(), RuntimeJars.discover());
        minion.destroy();

        minion.close();
        minion.close();
    }

    @Test
    void whateverTheMinionPrintedIsKeptForDiagnostics() {
        try (MinionProcess minion = MinionProcess.start(fixtureModule(), RuntimeJars.discover())) {
            minion.prepareTests(fixtureModule().testClassPaths());

            // Nothing has gone wrong, so there may be nothing to show; what matters is that
            // asking is safe and the drain thread has not broken anything.
            assertFalse(minion.output() == null);
            assertTrue(minion.isAlive());
        }
    }

    /**
     * A test id that does not resolve comes back as a failure, and jzap never sends one.
     *
     * <p>Recorded rather than asserted as desirable, because taken alone it would be the wrong
     * answer: a selector that matched nothing is not evidence that a mutant died. What makes it
     * harmless is that every id jzap runs came from its own coverage phase. A reused coverage map
     * cannot reintroduce a stale id either, since its key covers the bytecode of every test class
     * -- renaming or deleting a test changes the key and discards the map.
     *
     * <p>If selection ever gains a source that is not the coverage phase's own discovery, this is
     * the behaviour that would have to change with it.
     */
    @Test
    void anUnresolvableTestIdPresentsAsAFailure() {
        try (MinionProcess minion = MinionProcess.start(fixtureModule(), RuntimeJars.discover())) {
            minion.prepareTests(fixtureModule().testClassPaths());

            MinionProcess.MutantOutcome outcome = minion.runTests(
                    List.of("[engine:junit-jupiter]/[class:does.NotExist]/[method:nope()]"),
                    1_000_000L, 30_000, io.github.huyz0.jzap.agent.MutantSwitch.NONE);

            assertEquals(io.github.huyz0.jzap.wire.Wire.OUTCOME_FAILED, outcome.code(),
                    "the launcher reports an unresolved selector as a failed container");
        }
    }

    @Test
    void runningTestsBeforePreparingTheHarnessIsRefused() {
        try (MinionProcess minion = MinionProcess.start(fixtureModule(), RuntimeJars.discover())) {
            WireException e = assertThrows(WireException.class, () -> minion.runTests(
                    List.of("whatever"), 1_000L, 5_000, io.github.huyz0.jzap.agent.MutantSwitch.NONE));

            assertTrue(e.getMessage().contains("PREPARE_TESTS")
                            || e.getMessage().contains("LIST_TESTS"),
                    "the message has to name the command that was missing: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ timeouts and refusals

    /**
     * The wall-clock backstop, for the cases the loop guard cannot see.
     *
     * <p>The guard counts iterations, which is what makes a runaway mutant a deterministic
     * verdict. It cannot see a mutant that blocks rather than loops, or one whose exception is
     * swallowed by code catching Throwable, so the read timeout stays as a backstop -- and a
     * timeout has to arrive as HungException, because the only way to stop the JVM is to kill it.
     */
    @Test
    void aTestThatOutlastsItsTimeoutIsReportedAsHung() {
        ModuleModel hang = Fixture.hang().model(io.github.huyz0.jzap.model.Scope.all()).modules().get(0);

        try (MinionProcess minion = MinionProcess.start(hang, RuntimeJars.discover())) {
            minion.prepareTests(hang.testClassPaths());
            List<String> tests = discoverIn(hang);

            MinionProcess.HungException e = assertThrows(MinionProcess.HungException.class,
                    () -> minion.runTests(tests, Long.MAX_VALUE, 1, MutantSwitch.NONE));

            assertTrue(e.getMessage().contains("did not finish within"), e.getMessage());
            assertTrue(e.getMessage().contains("infinite loop"),
                    "the message should say what this usually means: " + e.getMessage());
            minion.destroy();
        }
    }

    @Test
    void aCoverageRunThatOutlastsItsTimeoutIsReportedAsHung() {
        ModuleModel hang = Fixture.hang().model(io.github.huyz0.jzap.model.Scope.all()).modules().get(0);

        try (MinionProcess minion = MinionProcess.start(hang, RuntimeJars.discover())) {
            // listTests is what builds the harness in the coverage phase.
            List<String> tests = minion.listTests(hang.testClassPaths());
            assertFalse(tests.isEmpty());

            MinionProcess.HungException e = assertThrows(MinionProcess.HungException.class,
                    () -> minion.runTestForCoverage(tests.get(0), 1));

            assertTrue(e.getMessage().contains(tests.get(0)),
                    "the message names the test, since the run is abandoned: " + e.getMessage());
            minion.destroy();
        }
    }

    /**
     * Bytecode the JVM refuses is reported, and only once the class is actually loaded.
     *
     * <p>Installing an override for a class that has not been loaded cannot fail: the bytes are
     * recorded and the load-time transformer serves them later. It is retransforming a loaded
     * class that the JVM can reject, which is why the tests are run first here.
     */
    @Test
    void bytecodeTheJvmRefusesIsReportedRatherThanInstalledSilently() {
        ModuleModel module = fixtureModule();
        try (MinionProcess minion = MinionProcess.start(module, RuntimeJars.discover())) {
            List<String> tests = minion.listTests(module.testClassPaths());
            // Running them loads the fixture's classes, which is what makes the next call a
            // retransformation rather than a recording.
            minion.runTests(tests, Long.MAX_VALUE, 60_000, MutantSwitch.NONE);

            WireException e = assertThrows(WireException.class,
                    () -> minion.setOverride("sample.Discount", new byte[]{1, 2, 3, 4}));

            assertTrue(e.getMessage().contains("sample.Discount"),
                    "the message has to name the class: " + e.getMessage());
            assertTrue(minion.isAlive(),
                    "one unusable mutant must not cost the whole analysis JVM");
        }
    }

    @Test
    void anOverrideForAClassNotYetLoadedIsRecordedWithoutComplaint() {
        ModuleModel module = fixtureModule();
        try (MinionProcess minion = MinionProcess.start(module, RuntimeJars.discover())) {
            minion.prepareTests(module.testClassPaths());

            // Nothing is loaded yet, so there is nothing to retransform and nothing to reject.
            minion.setOverride("sample.NeverLoaded", new byte[]{1, 2, 3, 4});
            minion.clearOverrides();

            assertTrue(minion.isAlive());
        }
    }

    private static List<String> discoverIn(ModuleModel module) {
        try (MinionProcess discovery = MinionProcess.start(module, RuntimeJars.discover())) {
            return discovery.listTests(module.testClassPaths());
        }
    }
}
