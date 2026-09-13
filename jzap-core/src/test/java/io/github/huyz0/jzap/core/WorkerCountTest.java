package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many analysis JVMs a given amount of work justifies.
 *
 * <p>This rule exists because more threads measured *slower*: once the per-mutant cost had fallen
 * to about 1.5 ms, twenty workers on a two-second job took 4.06s against one worker's 3.20s,
 * because the run was mostly twenty cold JVM startups. The rule is therefore a performance
 * decision with a measured origin, and worth pinning so a later change cannot quietly undo it.
 *
 * <p>Checked here rather than through an analysis because no fixture in this repository is large
 * enough to justify a second worker: the sample fixture is a few tens of milliseconds of work.
 * A test that asked for eight threads there would be asserting on a single-threaded run.
 */
class WorkerCountTest {

    /** Work that justifies one more worker, as MutantExecutor defines it: 2 x JVM startup. */
    private static final long PER_WORKER = 500;

    @Test
    void oneThreadRequestedIsAlwaysOneWorker() {
        assertEquals(1, MutantExecutor.workerCountFor(1, 0));
        assertEquals(1, MutantExecutor.workerCountFor(1, 10_000_000));
    }

    @Test
    void zeroOrFewerRequestedIsStillOneWorker() {
        assertEquals(1, MutantExecutor.workerCountFor(0, 10_000),
                "there is always someone to do the work");
        assertEquals(1, MutantExecutor.workerCountFor(-1, 10_000));
    }

    @Test
    void aTinyJobGetsOneWorkerHoweverManyWereAskedFor() {
        assertEquals(1, MutantExecutor.workerCountFor(20, 0));
        assertEquals(1, MutantExecutor.workerCountFor(20, 24),
                "the sample fixture's scale: twenty JVM startups would dominate it");
        assertEquals(1, MutantExecutor.workerCountFor(20, PER_WORKER - 1));
    }

    @Test
    void eachFurtherChunkOfWorkJustifiesOneMoreWorker() {
        assertEquals(1, MutantExecutor.workerCountFor(20, PER_WORKER));
        assertEquals(2, MutantExecutor.workerCountFor(20, 2 * PER_WORKER));
        assertEquals(4, MutantExecutor.workerCountFor(20, 4 * PER_WORKER));
        assertEquals(20, MutantExecutor.workerCountFor(20, 20 * PER_WORKER));
    }

    @Test
    void theRequestIsNeverExceededHoweverMuchWorkThereIs() {
        assertEquals(2, MutantExecutor.workerCountFor(2, 1_000 * PER_WORKER),
                "asking for two threads means two, not as many as the work would bear");
        assertEquals(4, MutantExecutor.workerCountFor(4, Long.MAX_VALUE / 2));
    }

    @Test
    void theCountRisesWithWorkAndNeverFalls() {
        int previous = 1;
        for (long work = 0; work <= 40 * PER_WORKER; work += PER_WORKER / 2) {
            int workers = MutantExecutor.workerCountFor(20, work);
            assertTrue(workers >= previous,
                    "more work must never mean fewer workers, at " + work + "ms");
            assertTrue(workers >= 1 && workers <= 20, "out of range at " + work + "ms");
            previous = workers;
        }
    }

    @Test
    void aLargeRealisticJobUsesTheThreadsItWasGiven() {
        // The bench fixture's shape: 1080 mutants at a couple of milliseconds each.
        assertEquals(4, MutantExecutor.workerCountFor(4, 1080 * 2));
        assertEquals(4, MutantExecutor.workerCountFor(20, 1080 * 2),
                "about 2160ms of work justifies four JVMs and not twenty, which is the "
                        + "measurement this rule came from");
    }
}
