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
}
