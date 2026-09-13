package io.github.huyz0.jzap.agent;

/**
 * Thrown when mutated code loops far more than the original ever did.
 *
 * <p>An {@link Error} rather than an exception so it passes through the {@code catch (Exception)}
 * blocks that ordinary code is full of. Code that catches {@link Throwable} can still swallow it,
 * which is why the wall-clock timeout remains as a backstop.
 */
public final class RunawayLoopError extends Error {

    private final long ticks;
    private final long limit;

    public RunawayLoopError(long ticks, long limit) {
        super("mutated code executed " + ticks + " loop iterations, past the limit of " + limit
                + " derived from what the unmutated code needed");
        this.ticks = ticks;
        this.limit = limit;
    }

    public long ticks() {
        return ticks;
    }

    public long limit() {
        return limit;
    }
}
