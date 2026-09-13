package stateful;

/**
 * Code that keeps static state across calls, which is what makes reusing an analysis JVM between
 * mutants a question rather than an obvious optimisation.
 */
public class Counters {

    /** Survives for the life of the JVM, so it survives from one mutant to the next. */
    private static int callsSoFar;

    /** A memoised answer: once computed, later calls never execute the computation again. */
    private static Integer memoised;

    public int count() {
        callsSoFar = callsSoFar + 1;
        return callsSoFar;
    }

    public int expensive(int input) {
        if (memoised == null) {
            memoised = input * 2;
        }
        return memoised;
    }

    public int plain(int a, int b) {
        return a + b;
    }
}
