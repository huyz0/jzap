package io.github.huyz0.jzap.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where a run's time went.
 *
 * <p>Reported so the next optimisation is chosen by measurement rather than by guess. Every one so
 * far was picked this way: the schemata engine because redefinition dominated, test batching
 * because launcher startup did, and parallelising the coverage phase was rejected because the
 * breakdown showed under 7% in it to win.
 *
 * <p>Accumulating rather than assigning, because most of these are summed across analysis workers
 * running at once. A phase's total is therefore CPU time across workers and can exceed the wall
 * clock of the phase that contains it.
 */
final class PhaseTimings {

    /**
     * One accumulating measurement.
     *
     * @param durations whether the value is nanoseconds to be reported as milliseconds, or a
     *                  plain count to be reported as it stands
     */
    private record Slot(AtomicLong value, boolean durations) {
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** Adds the time elapsed since {@code startNanos} to {@code name}. */
    public void addSince(String name, long startNanos) {
        slot(name, true).value().addAndGet(System.nanoTime() - startNanos);
    }

    /** Adds a count to {@code name}, which is reported unscaled. */
    public void count(String name, long amount) {
        slot(name, false).value().addAndGet(amount);
    }

    private synchronized Slot slot(String name, boolean durations) {
        return slots.computeIfAbsent(name, key -> new Slot(new AtomicLong(), durations));
    }

    /** The measurements so far, durations in milliseconds, in the order they were first recorded. */
    public synchronized Map<String, Long> toMap() {
        Map<String, Long> out = new LinkedHashMap<>();
        slots.forEach((name, slot) -> out.put(name,
                slot.durations() ? slot.value().get() / 1_000_000L : slot.value().get()));
        return out;
    }
}
