package parallel;

/**
 * One of two classes whose analysis is slow enough to justify more than one analysis JVM.
 *
 * <p>The engine caps workers at what the work justifies, because starting one costs about a
 * quarter of a second. Every other fixture here is a few tens of milliseconds of work and so
 * always collapses to a single worker, which leaves the concurrent execution path untested. The
 * slowness lives in the tests rather than here; see BetaTest.
 */
public final class Beta {

    public int combine(int a, int b) {
        return a + b;
    }

    public int scale(int a, int b) {
        return a * b;
    }

    public boolean atLeast(int a, int b) {
        return a >= b;
    }

    public int adjust(int a) {
        return a - 1;
    }
}
