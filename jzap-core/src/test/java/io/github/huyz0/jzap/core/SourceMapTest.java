package io.github.huyz0.jzap.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SMAP shown here is the one kotlinc actually emitted for the Kotlin fixture, copied from
 * {@code javap -v}, rather than one written from the specification.
 */
class SourceMapTest {

    private static final String KOTLIN_SMAP = """
            SMAP
            Pricing.kt
            Kotlin
            *S Kotlin
            *F
            + 1 Pricing.kt
            ksample/PricingKt
            *L
            1#1,50:1
            40#1,2:51
            40#1,2:53
            *S KotlinDebug
            *F
            + 1 Pricing.kt
            ksample/PricingKt
            *L
            45#1:51,2
            47#1:53,2
            *E
            """;

    @Test
    void mapsInlinedLinesBackToTheirDeclaration() {
        SourceMap map = SourceMap.parse(KOTLIN_SMAP);

        // Both call sites inlined the same two lines of withSurcharge.
        assertEquals(40, map.toSourceLine(51));
        assertEquals(41, map.toSourceLine(52));
        assertEquals(40, map.toSourceLine(53));
        assertEquals(41, map.toSourceLine(54));
    }

    @Test
    void leavesOrdinaryLinesAlone() {
        SourceMap map = SourceMap.parse(KOTLIN_SMAP);

        assertEquals(10, map.toSourceLine(10));
        assertEquals(40, map.toSourceLine(40));
        assertFalse(map.isInlined(10));
        assertTrue(map.isInlined(51), "51 is a copy of 40, not a line anyone wrote");
    }

    @Test
    void readsOnlyTheFirstStratum() {
        SourceMap map = SourceMap.parse(KOTLIN_SMAP);

        // The KotlinDebug stratum maps 51 to the call site at 45. That is what a debugger wants
        // and the opposite of what a report wants: a mutant in an inlined body is a mutation of
        // the inline function's source, not of the line that called it.
        assertEquals(40, map.toSourceLine(51));
    }

    @Test
    void namesTheFileALineCameFrom() {
        SourceMap map = SourceMap.parse(KOTLIN_SMAP);

        assertEquals("Pricing.kt", map.toSourceFile(51).orElseThrow());
    }

    @Test
    void classesWithoutAMapAreUnaffected() {
        assertTrue(SourceMap.parse(null).isEmpty());
        assertTrue(SourceMap.parse("").isEmpty());
        assertEquals(42, SourceMap.parse(null).toSourceLine(42));
    }

    @Test
    void malformedInputYieldsAnEmptyMapRatherThanAnError() {
        // A debug attribute is a hint. Failing an entire analysis over a malformed one would be
        // a poor trade, so this must not throw.
        assertTrue(SourceMap.parse("SMAP\nbroken").isEmpty());
        assertTrue(SourceMap.parse("SMAP\nx\nKotlin\n*S Kotlin\n*L\nnonsense\n*E\n").isEmpty());
    }

    // ------------------------------------------------------------ the shapes kotlinc also emits

    @Test
    void aFileEntryWithNoRepeatCountIsASingleLine() {
        // The "input,repeat:output" form is optional; a plain "input:output" maps one line.
        SourceMap map = SourceMap.parse("""
                SMAP
                Single.kt
                Kotlin
                *S Kotlin
                *F
                + 1 Single.kt
                pkg/SingleKt
                *L
                7#1:100
                *E
                """);

        assertEquals(7, map.toSourceLine(100));
        assertTrue(map.isInlined(100));
    }

    @Test
    void anOutputLineRangeMapsEveryLineInIt() {
        SourceMap map = SourceMap.parse("""
                SMAP
                Range.kt
                Kotlin
                *S Kotlin
                *F
                + 1 Range.kt
                pkg/RangeKt
                *L
                10#1,3:200,1
                *E
                """);

        assertEquals(10, map.toSourceLine(200));
        assertEquals(11, map.toSourceLine(201));
        assertEquals(12, map.toSourceLine(202));
    }

    @Test
    void aFileIdThatIsNotANumberIsSkippedRatherThanFailing() {
        // The *F section allows a path continuation line that carries no id of its own.
        SourceMap map = SourceMap.parse("""
                SMAP
                Cont.kt
                Kotlin
                *S Kotlin
                *F
                + 1 Cont.kt
                pkg/very/long/path/ContKt
                *L
                5#1:60
                *E
                """);

        assertEquals(5, map.toSourceLine(60),
                "a continuation line must not stop the line table being read");
    }

    @Test
    void aLineEntryWithUnparseableNumbersIsIgnored() {
        SourceMap map = SourceMap.parse("""
                SMAP
                Bad.kt
                Kotlin
                *S Kotlin
                *F
                + 1 Bad.kt
                pkg/BadKt
                *L
                notanumber#1:70
                5#1:notanumber
                8#1:80
                *E
                """);

        assertEquals(8, map.toSourceLine(80),
                "the good entry still has to be read past the bad ones");
        assertEquals(70, map.toSourceLine(70), "and a bad one leaves its line unmapped");
    }

    @Test
    void aFileSectionEntryWithNoSpaceIsIgnored() {
        SourceMap map = SourceMap.parse("""
                SMAP
                NoSpace.kt
                Kotlin
                *S Kotlin
                *F
                + 1
                *L
                3#1:40
                *E
                """);

        assertEquals(3, map.toSourceLine(40));
    }

    @Test
    void anEmptyOrBlankDebugAttributeYieldsAnEmptyMap() {
        for (String debug : new String[]{null, "", "   ", "not an smap at all"}) {
            SourceMap map = SourceMap.parse(debug);
            assertEquals(42, map.toSourceLine(42),
                    "an unmapped line is itself, for input " + debug);
            assertFalse(map.isInlined(42));
        }
    }
}
