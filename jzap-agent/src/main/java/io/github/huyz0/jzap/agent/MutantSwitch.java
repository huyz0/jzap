package io.github.huyz0.jzap.agent;

/**
 * Which mutant is currently active.
 *
 * <p>Under mutant schemata every mutant in a class is compiled in at once, and selecting one is a
 * single field write instead of redefining a class. That is the whole point: redefinition forces
 * the JVM to discard and re-verify a class and throw away its JIT-compiled code, and it happens
 * once per mutant.
 *
 * <p>A plain global rather than a thread local. Each analysis worker owns its own JVM, so there is
 * nothing to isolate from; and a thread local would be actively wrong, because a test that hands
 * work to another thread would see the unmutated code there.
 *
 * <p>Volatile because a thread pool inside the code under test can outlive a mutant. Without it, a
 * pooled thread could keep reading a stale value and quietly run the wrong mutant.
 */
public final class MutantSwitch {

    /** No mutant active: every schemata call falls through to the original behaviour. */
    public static final int NONE = -1;

    private static volatile int active = NONE;

    private MutantSwitch() {
    }

    public static int active() {
        return active;
    }

    public static void activate(int mutantIndex) {
        active = mutantIndex;
    }

    public static void deactivate() {
        active = NONE;
    }
}
