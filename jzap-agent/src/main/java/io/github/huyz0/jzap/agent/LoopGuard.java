package io.github.huyz0.jzap.agent;

/**
 * Counts loop iterations so a mutant that never returns can be detected by what it does rather
 * than by how long it takes.
 *
 * <p>A wall-clock timeout is the obvious way to notice an infinite loop and the wrong one: the
 * verdict then depends on how loaded the machine is, which makes reports differ between runs and
 * makes them unusable as build-cache outputs. Counting iterations gives the same answer every
 * time.
 *
 * <p>The limit is derived from the unmutated code's own behaviour, measured during the coverage
 * run, so a legitimately long-running loop is not mistaken for a runaway one. This is the scheme
 * PIT's own design notes recommend over timings.
 *
 * <p>One global counter rather than one per method: the cost is a static increment per loop
 * iteration, and per-method counters would multiply that for no benefit, since a single runaway
 * loop is all that needs detecting.
 */
public final class LoopGuard {

    private static final long DISARMED = Long.MAX_VALUE;

    private static long limit = DISARMED;
    private static long ticks;

    private LoopGuard() {
    }

    /** Called at every loop back edge in instrumented code. */
    public static void tick() {
        if (++ticks > limit) {
            long exceeded = ticks;
            ticks = 0;
            throw new RunawayLoopError(exceeded, limit);
        }
    }

    /** Starts counting with a limit. Zero or less means count without ever tripping. */
    public static void arm(long iterationLimit) {
        limit = iterationLimit <= 0 ? DISARMED : iterationLimit;
        ticks = 0;
    }

    public static void disarm() {
        limit = DISARMED;
        ticks = 0;
    }

    /** Iterations counted since the last {@link #arm}. */
    public static long ticks() {
        return ticks;
    }
}
