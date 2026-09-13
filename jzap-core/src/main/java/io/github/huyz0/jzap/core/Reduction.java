package io.github.huyz0.jzap.core;

/**
 * What the filters dropped, for the reduction report.
 *
 * <p>Reported per reason rather than as one total, because the reasons are not equally safe. A
 * mutant dropped as equivalent to the original could never have been killed, so dropping it costs
 * nothing; a mutant dropped as the second on its line might have been the one a reviewer needed.
 * A total would hide which of those a run traded away.
 *
 * @param equivalent compiled to the same bytecode as the unmutated original
 * @param duplicate  compiled to the same bytecode as another mutant
 * @param arid       in code that reports rather than decides
 * @param onePerLine beyond the first mutant on its source line
 * @param kotlinJunk in a construct the Kotlin compiler generated
 */
public record Reduction(int equivalent, int duplicate, int arid, int onePerLine, int kotlinJunk) {

    public int total() {
        return equivalent + duplicate + arid + onePerLine + kotlinJunk;
    }

    /**
     * The running counts, while discovery is still going.
     *
     * <p>Mutable and package-private: a record cannot count, and five separate fields on
     * {@link MutationEngine} was the thing this replaced.
     */
    static final class Tally {
        int equivalent;
        int duplicate;
        int arid;
        int onePerLine;
        int kotlinJunk;

        Reduction snapshot() {
            return new Reduction(equivalent, duplicate, arid, onePerLine, kotlinJunk);
        }
    }
}
