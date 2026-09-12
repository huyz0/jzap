package io.github.huyz0.jzap.agent;

/**
 * Probe sink for the coverage phase. Instrumented classes call {@link #hit(int)} once per
 * executed source line.
 *
 * <p>Per-test coverage only: the array is read and cleared between tests, so nothing needs
 * to be gathered from individual instrumented classes at the end of a run. A plain
 * {@code boolean[]} with unsynchronised writes is deliberate — a lost write under a data
 * race can only ever under-report a hit, which costs a redundant test execution later and
 * never produces a wrong verdict.
 */
public final class CoverageRecorder {

    private static boolean[] probes = new boolean[0];

    private CoverageRecorder() {
    }

    public static void init(int probeCount) {
        probes = new boolean[probeCount];
    }

    public static void hit(int probeId) {
        boolean[] p = probes;
        if (probeId < p.length) {
            p[probeId] = true;
        }
    }

    /** Ids hit since the last call, then resets. */
    public static int[] drain() {
        boolean[] p = probes;
        int n = 0;
        for (boolean b : p) {
            if (b) {
                n++;
            }
        }
        int[] out = new int[n];
        int i = 0;
        for (int id = 0; id < p.length; id++) {
            if (p[id]) {
                out[i++] = id;
                p[id] = false;
            }
        }
        return out;
    }

    public static int probeCount() {
        return probes.length;
    }
}
