package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many analysis JVMs a given job justifies.
 *
 * <p>Two bounds, and the smaller wins. Both exist because more workers measured *slower*:
 *
 * <ul>
 *   <li><b>Work.</b> Twenty workers on a two-second job took 4.06s against one worker's 3.20s,
 *       because the run was mostly twenty cold JVM startups.
 *   <li><b>Cores.</b> Every worker is a JVM running a real test suite, so asking for more of them
 *       than the machine has cores buys timeslicing and a cold start each.
 * </ul>
 *
 * <p>What these bounds deliberately do not try to decide is how many workers are worth having when
 * cores are spare. On a four-core runner the bench fixture measured 0.82x at two workers and 0.69x
 * at four; fixtures/parallel-java, whose tests sleep, gains from every worker available. Both come
 * to roughly 800-1100ms of estimated work per worker, so no threshold here can separate them --
 * what separates them is CPU-bound against waiting, which nothing measures. That is why the
 * default thread count is one, and this rule only trims a request that cannot physically pay off.
 *
 * <p>Checked at this level rather than through an analysis because no fixture in this repository
 * is large enough to justify a second worker on work alone: the sample fixture is a few tens of
 * milliseconds. A test that asked for eight threads there would be asserting on a single-threaded
 * run.
 */
class WorkerCountTest {

    /** Work that justifies one more worker, as MutantExecutor defines it: 2 x JVM startup. */
    private static final long PER_WORKER = 500;

    /** Enough of the other bound that the one under test is the only one that can bind. */
    private static final long AMPLE_WORK = 1_000 * PER_WORKER;
    private static final int AMPLE_CORES = 1_000;

    private static int workers(int requested, long work, int cores) {
        return MutantExecutor.workerCountFor(requested, work, cores);
    }

    @Test
    void oneThreadRequestedIsAlwaysOneWorker() {
        assertEquals(1, workers(1, 0, AMPLE_CORES));
        assertEquals(1, workers(1, 10_000_000, AMPLE_CORES));
    }

    @Test
    void zeroOrFewerRequestedIsStillOneWorker() {
        assertEquals(1, workers(0, AMPLE_WORK, AMPLE_CORES),
                "there is always someone to do the work");
        assertEquals(1, workers(-1, AMPLE_WORK, AMPLE_CORES));
    }

    // ------------------------------------------------------------------ the work bound

    @Test
    void aTinyJobGetsOneWorkerHoweverManyWereAskedFor() {
        assertEquals(1, workers(20, 0, AMPLE_CORES));
        assertEquals(1, workers(20, 24, AMPLE_CORES),
                "the sample fixture's scale: twenty JVM startups would dominate it");
        assertEquals(1, workers(20, PER_WORKER - 1, AMPLE_CORES));
    }

    @Test
    void eachFurtherChunkOfWorkJustifiesOneMoreWorker() {
        assertEquals(1, workers(20, PER_WORKER, AMPLE_CORES));
        assertEquals(2, workers(20, 2 * PER_WORKER, AMPLE_CORES));
        assertEquals(4, workers(20, 4 * PER_WORKER, AMPLE_CORES));
        assertEquals(20, workers(20, 20 * PER_WORKER, AMPLE_CORES));
    }

    // ----------------------------------------------------------------- the cores bound

    /**
     * The case the CI benchmark exposed.
     *
     * <p>Work alone justified four workers for the bench fixture's ~2160ms, and four is what a
     * four-core machine's default thread count used to be. Four analysis JVMs plus this one on
     * four cores measured 0.69x against a single thread.
     */
    @Test
    void workersNeverExceedTheCoresLessOneForThisJvm() {
        assertEquals(3, workers(4, 1080 * 2, 4),
                "four cores runs three workers plus the controller, not four plus the controller");
        assertEquals(3, workers(20, AMPLE_WORK, 4));
        assertEquals(7, workers(20, AMPLE_WORK, 8));
        assertEquals(19, workers(20, AMPLE_WORK, 20));
    }

    @Test
    void aThreadCountFarAboveTheCoreCountIsTrimmedToIt() {
        assertEquals(3, workers(32, AMPLE_WORK, 4),
                "asking for 32 threads on four cores used to start 32 JVMs to timeslice 4 cores");
    }

    @Test
    void aSingleCoreMachineStillGetsAWorker() {
        assertEquals(1, workers(20, AMPLE_WORK, 1));
        assertEquals(1, workers(20, AMPLE_WORK, 0),
                "a machine reporting no processors is not a reason to do nothing");
    }

    // ---------------------------------------------------------------------- invariants

    @Test
    void theRequestIsNeverExceededHoweverLargeTheJob() {
        assertEquals(2, workers(2, AMPLE_WORK, AMPLE_CORES),
                "asking for two threads means two, not as many as the job would bear");
        assertEquals(4, workers(4, Long.MAX_VALUE / 2, AMPLE_CORES));
    }

    @Test
    void theCountRisesWithWorkAndNeverFalls() {
        int previous = 1;
        for (long work = 0; work <= 40 * PER_WORKER; work += PER_WORKER / 2) {
            int count = workers(20, work, AMPLE_CORES);
            assertTrue(count >= previous,
                    "more work must never mean fewer workers, at " + work + "ms");
            assertTrue(count >= 1 && count <= 20, "out of range at " + work + "ms");
            previous = count;
        }
    }

    @Test
    void theCountRisesWithCoresAndNeverFalls() {
        int previous = 1;
        for (int cores = 0; cores <= 40; cores++) {
            int count = workers(20, AMPLE_WORK, cores);
            assertTrue(count >= previous, "more cores must never mean fewer workers, at " + cores);
            assertTrue(count >= 1 && count <= 20, "out of range at " + cores + " cores");
            previous = count;
        }
    }

    // ------------------------------------------------- what the user is told about it

    /**
     * "using 1 of 4 thread(s)" with no reason invites a bug report, so each bound explains itself.
     *
     * <p>Asserted on the substance rather than the wording: which constraint was named, not how.
     */
    @Test
    void theMessageNamesTheBoundThatApplied() {
        String cores = MutantExecutor.why(20, AMPLE_WORK, 4, 3);
        assertTrue(cores.contains("4 core(s)"), cores);
        assertTrue(cores.contains("timeslice"),
                "it has to say why more workers do not help, not just that there are 4: " + cores);

        String work = MutantExecutor.why(20, 2 * PER_WORKER, AMPLE_CORES, 2);
        assertTrue(work.contains("1000ms"), work);
        assertTrue(work.contains("start cold"),
                "the cost being amortised is the cold start: " + work);
    }

    @Test
    void thereIsNoMessageWhenNothingWasTrimmed() {
        assertEquals("no bound applied", MutantExecutor.why(4, AMPLE_WORK, AMPLE_CORES, 4));
    }

    @Test
    void theSmallerBoundWins() {
        assertEquals(1, workers(20, PER_WORKER, AMPLE_CORES), "work binds");
        assertEquals(1, workers(20, AMPLE_WORK, 2), "cores bind");
    }
}
