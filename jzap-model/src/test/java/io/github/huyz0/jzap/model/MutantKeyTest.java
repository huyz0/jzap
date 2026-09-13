package io.github.huyz0.jzap.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutant key: jzap's identity for a mutant.
 *
 * <p>Everything agrees on this string. The cache looks verdicts up by it, the parity harness
 * compares inventories by it, and every report names mutants with it. Round-tripping has to be
 * exact, and the ordering has to be total: report bytes are only reproducible if two runs sort
 * the same inventory the same way.
 */
class MutantKeyTest {

    private static MutantKey key(String className, String method, String descriptor,
                                 int line, String mutator, int ordinal) {
        return new MutantKey(className, method, descriptor, line, mutator, ordinal);
    }

    @Test
    void rendersAsTheFormWrittenToReportsAndCaches() {
        assertEquals("ex.Calc::add(II)I::4::MATH#0",
                key("ex.Calc", "add", "(II)I", 4, "MATH", 0).asString());
    }

    @Test
    void toStringIsTheSameRendering() {
        MutantKey k = key("ex.Calc", "add", "(II)I", 4, "MATH", 0);
        assertEquals(k.asString(), k.toString(),
                "a key in a log message should be the key the cache would show");
    }

    @Test
    void roundTripsThroughItsOwnRendering() {
        List<MutantKey> keys = List.of(
                key("ex.Calc", "add", "(II)I", 4, "MATH", 0),
                key("ex.Calc", "<init>", "()V", 1, "NEGATE_CONDITIONALS", 3),
                key("ex.Calc", "<clinit>", "()V", 0, "VOID_METHOD_CALLS", 0),
                key("a.b.Outer$Inner", "get", "()Ljava/lang/String;", 99, "EMPTY_RETURNS", 7),
                key("ex.Generic", "map", "(Ljava/util/List;)Ljava/util/Map;", 12, "MATH", 1));

        for (MutantKey original : keys) {
            assertEquals(original, MutantKey.parse(original.asString()),
                    "failed to round-trip " + original.asString());
        }
    }

    @Test
    void parsesADescriptorContainingTheDelimiter() {
        // A descriptor holds slashes and semicolons, and a method can be named almost anything;
        // the split has to be bounded so a type name cannot be mistaken for a field separator.
        MutantKey parsed = MutantKey.parse("ex.C::f(Ljava/lang/String;)V::7::MATH#2");

        assertEquals("ex.C", parsed.className());
        assertEquals("f", parsed.methodName());
        assertEquals("(Ljava/lang/String;)V", parsed.descriptor());
        assertEquals(7, parsed.line());
        assertEquals("MATH", parsed.mutator());
        assertEquals(2, parsed.ordinal());
    }

    @Test
    void parsingRejectsWhatIsNotAKeyAndSaysWhatWasWrong() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MutantKey.parse("nonsense")).getMessage().contains("not a mutant key"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MutantKey.parse("ex.C::noDescriptor::7::MATH#0"))
                .getMessage().contains("missing descriptor"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MutantKey.parse("ex.C::f()V::7::MATH"))
                .getMessage().contains("missing ordinal"));
    }

    @Test
    void aKeyWithoutAClassOrMutatorIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> key(null, "f", "()V", 1, "MATH", 0));
        assertThrows(IllegalArgumentException.class,
                () -> key("  ", "f", "()V", 1, "MATH", 0));
        assertThrows(IllegalArgumentException.class,
                () -> key("ex.C", "f", "()V", 1, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> key("ex.C", "f", "()V", 1, " ", 0));
    }

    // ------------------------------------------------------------ ordering

    @Test
    void ordersByEveryFieldInTurn() {
        MutantKey base = key("ex.B", "m", "()V", 5, "MATH", 1);

        assertTrue(base.compareTo(key("ex.C", "m", "()V", 5, "MATH", 1)) < 0, "class first");
        assertTrue(base.compareTo(key("ex.B", "n", "()V", 5, "MATH", 1)) < 0, "then method");
        assertTrue(base.compareTo(key("ex.B", "m", "()Z", 5, "MATH", 1)) < 0, "then descriptor");
        assertTrue(base.compareTo(key("ex.B", "m", "()V", 6, "MATH", 1)) < 0, "then line");
        assertTrue(base.compareTo(key("ex.B", "m", "()V", 5, "NEGATE", 1)) < 0, "then mutator");
        assertTrue(base.compareTo(key("ex.B", "m", "()V", 5, "MATH", 2)) < 0, "then ordinal");
    }

    @Test
    void orderingIsTotalSoTwoRunsSortAnInventoryIdentically() {
        MutantKey a = key("ex.B", "m", "()V", 5, "MATH", 1);
        MutantKey same = key("ex.B", "m", "()V", 5, "MATH", 1);

        assertEquals(0, a.compareTo(same), "equal keys must compare equal");
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());

        List<MutantKey> unsorted = List.of(
                key("ex.B", "m", "()V", 5, "MATH", 2),
                key("ex.A", "z", "()V", 1, "MATH", 0),
                key("ex.B", "m", "()V", 5, "MATH", 1));
        List<MutantKey> sorted = unsorted.stream().sorted().toList();

        assertEquals(List.of("ex.A::z()V::1::MATH#0",
                        "ex.B::m()V::5::MATH#1",
                        "ex.B::m()V::5::MATH#2"),
                sorted.stream().map(MutantKey::asString).toList());
    }

    @Test
    void lineZeroMeansNoDebugInformationAndStillSortsFirst() {
        MutantKey noLine = key("ex.C", "m", "()V", 0, "MATH", 0);
        MutantKey withLine = key("ex.C", "m", "()V", 1, "MATH", 0);

        assertTrue(noLine.compareTo(withLine) < 0);
    }
}
