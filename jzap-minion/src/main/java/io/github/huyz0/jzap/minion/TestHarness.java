package io.github.huyz0.jzap.minion;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs tests through the JUnit Platform launcher, one at a time, addressed by unique id.
 *
 * <p>Going through the launcher rather than a build tool's test task is what makes per-test
 * coverage and single-test replay possible at all: a build tool will happily run a test
 * class, but jzap needs to run exactly one test and know precisely what it touched.
 */
final class TestHarness {

    /**
     * @param passed      every selected test passed
     * @param failingTest unique id of the first test that failed, when any did
     * @param testsRun    tests actually executed, which early exit keeps below the selection size
     * @param nonViable   the mutated class could not be linked or verified, so this is not a
     *                    genuine test failure
     * @param failureMessage first failure's type and message, so a baseline failure can be
     *                    reported to the user instead of merely counted
     */
    record Outcome(boolean passed, String failingTest, int testsRun, boolean nonViable,
                   String failureMessage) {
    }

    private final List<Path> classpathRoots;
    private final Launcher launcher = LauncherFactory.create();

    TestHarness(List<Path> classpathRoots) {
        this.classpathRoots = List.copyOf(classpathRoots);
    }

    /** Unique ids of every executable test found on the test class paths, in a stable order. */
    List<String> discover() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.copyOf(classpathRoots)))
                .build();
        TestPlan plan = launcher.discover(request);
        List<String> tests = new ArrayList<>();
        for (TestIdentifier root : plan.getRoots()) {
            collectTests(plan, root, tests);
        }
        return tests;
    }

    private void collectTests(TestPlan plan, TestIdentifier id, List<String> out) {
        if (id.isTest()) {
            out.add(id.getUniqueId());
        }
        for (TestIdentifier child : plan.getChildren(id)) {
            collectTests(plan, child, out);
        }
    }

    /**
     * @param stopOnFirstFailure stop as soon as a test fails. Worth roughly half the run time
     *                           according to PIT's own measurements, because most mutants are
     *                           killed by the first test that covers them.
     */
    Outcome run(List<String> testIds, boolean stopOnFirstFailure) {
        int run = 0;
        for (String testId : testIds) {
            ResultListener listener = new ResultListener();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectUniqueId(testId))
                    .build();
            try {
                launcher.execute(request, listener);
            } catch (LinkageError e) {
                return new Outcome(false, testId, run + 1, true, describe(e));
            }
            run++;
            if (listener.failed) {
                if (listener.nonViable) {
                    return new Outcome(false, testId, run, true, listener.failureMessage);
                }
                if (stopOnFirstFailure) {
                    return new Outcome(false, testId, run, false, listener.failureMessage);
                }
            }
        }
        return new Outcome(true, null, run, false, null);
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private static final class ResultListener implements TestExecutionListener {

        private boolean failed;
        private boolean nonViable;
        private String failureMessage;

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            if (result.getStatus() != TestExecutionResult.Status.FAILED) {
                return;
            }
            failed = true;
            result.getThrowable().ifPresent(t -> {
                if (failureMessage == null) {
                    failureMessage = describe(t);
                }
                if (isLinkageProblem(t)) {
                    nonViable = true;
                }
            });
        }

        /**
         * A mutant whose class will not verify or link is not evidence about the test suite;
         * reporting it as KILLED would inflate the score with a change no developer could
         * have made.
         */
        private static boolean isLinkageProblem(Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof VerifyError || c instanceof ClassFormatError
                        || c instanceof NoClassDefFoundError || c instanceof NoSuchMethodError
                        || c instanceof NoSuchFieldError || c instanceof IncompatibleClassChangeError) {
                    return true;
                }
                if (c.getCause() == c) {
                    break;
                }
            }
            return false;
        }
    }
}
