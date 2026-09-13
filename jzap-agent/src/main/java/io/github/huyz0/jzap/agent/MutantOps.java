package io.github.huyz0.jzap.agent;

/**
 * The operations a schemata-transformed class calls instead of doing the work inline.
 *
 * <p>Each one decides, from {@link MutantSwitch}, whether to behave as the original code did or as
 * one of the mutants seeded at that spot. Selecting a mutant is then a field write rather than a
 * class redefinition.
 *
 * <p>Every method is branch-free from the caller's point of view: the transformed method gains no
 * jumps and therefore no new stack map frames, so the transformer needs only COMPUTE_MAXS. The
 * alternative -- guarding each mutant with an {@code if} in the mutated method -- needs
 * COMPUTE_FRAMES, which means loading classes to work out common supertypes, which means an
 * analysis that fails on a classpath the transformer cannot fully resolve. This design avoids that
 * entire class of problem.
 *
 * <p>An id of {@link MutantSwitch#NONE} means no mutant of that kind exists at that spot, and the
 * comparison against the active id can then never match.
 *
 * <p>Generated in bulk and checked in, rather than generated at build time: it is read far more
 * often than it is changed, and a reader tracing a wrong verdict should be able to see exactly
 * what the mutated code does.
 */
public final class MutantOps {

    private MutantOps() {
    }


    /** integer addition, or the mutant that replaces it. */
    public static int iadd(int a, int b, int id) {
        return MutantSwitch.active() == id ? a - b : a + b;
    }

    /** integer subtraction, or the mutant that replaces it. */
    public static int isub(int a, int b, int id) {
        return MutantSwitch.active() == id ? a + b : a - b;
    }

    /** integer multiplication, or the mutant that replaces it. */
    public static int imul(int a, int b, int id) {
        return MutantSwitch.active() == id ? a / b : a * b;
    }

    /** integer division, or the mutant that replaces it. */
    public static int idiv(int a, int b, int id) {
        return MutantSwitch.active() == id ? a * b : a / b;
    }

    /** integer modulus, or the mutant that replaces it. */
    public static int irem(int a, int b, int id) {
        return MutantSwitch.active() == id ? a * b : a % b;
    }

    /** bitwise and, or the mutant that replaces it. */
    public static int iand(int a, int b, int id) {
        return MutantSwitch.active() == id ? a | b : a & b;
    }

    /** bitwise or, or the mutant that replaces it. */
    public static int ior(int a, int b, int id) {
        return MutantSwitch.active() == id ? a & b : a | b;
    }

    /** bitwise xor, or the mutant that replaces it. */
    public static int ixor(int a, int b, int id) {
        return MutantSwitch.active() == id ? a & b : a ^ b;
    }

    /** shift left, or the mutant that replaces it. */
    public static int ishl(int a, int b, int id) {
        return MutantSwitch.active() == id ? a >> b : a << b;
    }

    /** shift right, or the mutant that replaces it. */
    public static int ishr(int a, int b, int id) {
        return MutantSwitch.active() == id ? a << b : a >> b;
    }

    /** unsigned shift right, or the mutant that replaces it. */
    public static int iushr(int a, int b, int id) {
        return MutantSwitch.active() == id ? a << b : a >>> b;
    }

    /** long addition, or the mutant that replaces it. */
    public static long ladd(long a, long b, int id) {
        return MutantSwitch.active() == id ? a - b : a + b;
    }

    /** long subtraction, or the mutant that replaces it. */
    public static long lsub(long a, long b, int id) {
        return MutantSwitch.active() == id ? a + b : a - b;
    }

    /** long multiplication, or the mutant that replaces it. */
    public static long lmul(long a, long b, int id) {
        return MutantSwitch.active() == id ? a / b : a * b;
    }

    /** long division, or the mutant that replaces it. */
    public static long ldiv(long a, long b, int id) {
        return MutantSwitch.active() == id ? a * b : a / b;
    }

    /** long modulus, or the mutant that replaces it. */
    public static long lrem(long a, long b, int id) {
        return MutantSwitch.active() == id ? a * b : a % b;
    }

    /** bitwise and, or the mutant that replaces it. */
    public static long land(long a, long b, int id) {
        return MutantSwitch.active() == id ? a | b : a & b;
    }

    /** bitwise or, or the mutant that replaces it. */
    public static long lor(long a, long b, int id) {
        return MutantSwitch.active() == id ? a & b : a | b;
    }

    /** bitwise xor, or the mutant that replaces it. */
    public static long lxor(long a, long b, int id) {
        return MutantSwitch.active() == id ? a & b : a ^ b;
    }

    /** float addition, or the mutant that replaces it. */
    public static float fadd(float a, float b, int id) {
        return MutantSwitch.active() == id ? a - b : a + b;
    }

    /** float subtraction, or the mutant that replaces it. */
    public static float fsub(float a, float b, int id) {
        return MutantSwitch.active() == id ? a + b : a - b;
    }

    /** float multiplication, or the mutant that replaces it. */
    public static float fmul(float a, float b, int id) {
        return MutantSwitch.active() == id ? a / b : a * b;
    }

    /** float division, or the mutant that replaces it. */
    public static float fdiv(float a, float b, int id) {
        return MutantSwitch.active() == id ? a * b : a / b;
    }

    /** float modulus, or the mutant that replaces it. */
    public static float frem(float a, float b, int id) {
        return MutantSwitch.active() == id ? a * b : a % b;
    }

    /** double addition, or the mutant that replaces it. */
    public static double dadd(double a, double b, int id) {
        return MutantSwitch.active() == id ? a - b : a + b;
    }

    /** double subtraction, or the mutant that replaces it. */
    public static double dsub(double a, double b, int id) {
        return MutantSwitch.active() == id ? a + b : a - b;
    }

    /** double multiplication, or the mutant that replaces it. */
    public static double dmul(double a, double b, int id) {
        return MutantSwitch.active() == id ? a / b : a * b;
    }

    /** double division, or the mutant that replaces it. */
    public static double ddiv(double a, double b, int id) {
        return MutantSwitch.active() == id ? a * b : a / b;
    }

    /** double modulus, or the mutant that replaces it. */
    public static double drem(double a, double b, int id) {
        return MutantSwitch.active() == id ? a * b : a % b;
    }

    /** long shift, or the mutant that replaces it. */
    public static long lshl(long a, int b, int id) {
        return MutantSwitch.active() == id ? a >> b : a << b;
    }

    /** long shift, or the mutant that replaces it. */
    public static long lshr(long a, int b, int id) {
        return MutantSwitch.active() == id ? a << b : a >> b;
    }

    /** long shift, or the mutant that replaces it. */
    public static long lushr(long a, int b, int id) {
        return MutantSwitch.active() == id ? a << b : a >>> b;
    }

    /**
     * The branch condition for a == 0, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean ifeq(int a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a != 0;
        }
        return a == 0;
    }

    /**
     * The branch condition for a != 0, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean ifne(int a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a == 0;
        }
        return a != 0;
    }

    /**
     * The branch condition for a < 0, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean iflt(int a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a <= 0;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a >= 0;
        }
        return a < 0;
    }

    /**
     * The branch condition for a <= 0, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean ifle(int a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a < 0;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a > 0;
        }
        return a <= 0;
    }

    /**
     * The branch condition for a > 0, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean ifgt(int a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a >= 0;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a <= 0;
        }
        return a > 0;
    }

    /**
     * The branch condition for a >= 0, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean ifge(int a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a > 0;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a < 0;
        }
        return a >= 0;
    }

    /**
     * The branch condition for a == b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean icmpeq(int a, int b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a != b;
        }
        return a == b;
    }

    /**
     * The branch condition for a != b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean icmpne(int a, int b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a == b;
        }
        return a != b;
    }

    /**
     * The branch condition for a < b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean icmplt(int a, int b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a <= b;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a >= b;
        }
        return a < b;
    }

    /**
     * The branch condition for a <= b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean icmple(int a, int b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a < b;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a > b;
        }
        return a <= b;
    }

    /**
     * The branch condition for a > b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean icmpgt(int a, int b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a >= b;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a <= b;
        }
        return a > b;
    }

    /**
     * The branch condition for a >= b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean icmpge(int a, int b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (boundaryId != MutantSwitch.NONE && active == boundaryId) {
            return a > b;
        }
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a < b;
        }
        return a >= b;
    }

    /**
     * The branch condition for a == b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean acmpeq(Object a, Object b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a != b;
        }
        return a == b;
    }

    /**
     * The branch condition for a != b, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean acmpne(Object a, Object b, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a == b;
        }
        return a != b;
    }

    /**
     * The branch condition for a == null, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean isnull(Object a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a != null;
        }
        return a == null;
    }

    /**
     * The branch condition for a != null, or a mutant of it.
     *
     * @param boundaryId id of the moved-boundary mutant, or NONE where that mutator does not apply
     * @param negateId   id of the negated mutant, or NONE
     */
    public static boolean isnonnull(Object a, int boundaryId, int negateId) {
        int active = MutantSwitch.active();
        if (negateId != MutantSwitch.NONE && active == negateId) {
            return a == null;
        }
        return a != null;
    }

    /** A boolean return, or the mutant that forces it true or false. */
    public static boolean booleanReturn(boolean value, int trueId, int falseId) {
        int active = MutantSwitch.active();
        if (trueId != MutantSwitch.NONE && active == trueId) {
            return true;
        }
        if (falseId != MutantSwitch.NONE && active == falseId) {
            return false;
        }
        return value;
    }

    /** A numeric return, or the mutant that forces it to zero. */
    public static int intReturn(int value, int id) {
        return MutantSwitch.active() == id ? 0 : value;
    }

    public static long longReturn(long value, int id) {
        return MutantSwitch.active() == id ? 0L : value;
    }

    public static float floatReturn(float value, int id) {
        return MutantSwitch.active() == id ? 0f : value;
    }

    public static double doubleReturn(double value, int id) {
        return MutantSwitch.active() == id ? 0d : value;
    }

    /**
     * A reference return, or the mutant that replaces it with an empty value.
     *
     * <p>The empty value is pushed by the caller rather than chosen here, so this method needs no
     * knowledge of the return type. It is evaluated whether or not the mutant is active, which is
     * safe because every empty value jzap uses is a constant or a side-effect-free factory call.
     */
    public static Object emptyReturn(Object value, Object empty, int id) {
        return MutantSwitch.active() == id ? empty : value;
    }

    /** A local variable increment, or the mutant that negates it. */
    public static int increment(int value, int by, int id) {
        return MutantSwitch.active() == id ? value - by : value + by;
    }

    /** An arithmetic negation, or the mutant that removes it. */
    public static int ineg(int value, int id) {
        return MutantSwitch.active() == id ? value : -value;
    }

    public static long lneg(long value, int id) {
        return MutantSwitch.active() == id ? value : -value;
    }

    public static float fneg(float value, int id) {
        return MutantSwitch.active() == id ? value : -value;
    }

    public static double dneg(double value, int id) {
        return MutantSwitch.active() == id ? value : -value;
    }
}
