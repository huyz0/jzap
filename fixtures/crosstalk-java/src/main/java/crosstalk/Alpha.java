package crosstalk;

/**
 * A class whose mutants are analysed before {@link Beta}'s, and whose code {@code Beta}'s own
 * tests execute.
 *
 * <p>That combination is the point. Under the schemata engine a worker installs one schemata
 * class per class it analyses, and the mutant it selects is a single global index. If the
 * schemata built for this class is still installed while Beta's mutants are being analysed,
 * selecting Beta's mutant number n also selects this class's mutant number n -- so two mutants
 * are live at once and Beta's verdict is decided by a mutation somewhere else.
 */
public final class Alpha {

    /** Mutating this to a division is enough for {@link Beta#viaAlpha} to return the wrong value. */
    public static int scale(int n) {
        return n * 2;
    }
}
