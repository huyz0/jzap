package io.github.huyz0.jzap.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every schemata dispatch method, unmutated and mutated.
 *
 * <p>These are what a schemata-transformed class actually executes, so each one is the real
 * definition of what a mutant of that operator does. A wrong one does not fail loudly: it reports
 * a verdict about a mutant that is not the mutant the report names. The end-to-end tests would
 * only catch it if some fixture happened to depend on that operator, which is why each is pinned
 * here directly.
 *
 * <p>{@link MutantSwitch} is a global, so every test leaves it deactivated.
 */
class MutantOpsTest {

    /** An arbitrary active id. Any non-negative value the transformer could have assigned. */
    private static final int ACTIVE = 7;

    /** An id that is not the active one, so the original behaviour must be taken. */
    private static final int OTHER = 8;

    @AfterEach
    void deactivate() {
        MutantSwitch.deactivate();
    }

    // ------------------------------------------------------------ integer arithmetic

    @Test
    void integerArithmeticIsUnchangedWhileNoMutantIsActive() {
        assertEquals(7, MutantOps.iadd(3, 4, ACTIVE));
        assertEquals(-1, MutantOps.isub(3, 4, ACTIVE));
        assertEquals(12, MutantOps.imul(3, 4, ACTIVE));
        assertEquals(2, MutantOps.idiv(9, 4, ACTIVE));
        assertEquals(1, MutantOps.irem(9, 4, ACTIVE));
        assertEquals(0b0100, MutantOps.iand(0b0110, 0b1100, ACTIVE));
        assertEquals(0b1110, MutantOps.ior(0b0110, 0b1100, ACTIVE));
        assertEquals(0b1010, MutantOps.ixor(0b0110, 0b1100, ACTIVE));
        assertEquals(8, MutantOps.ishl(1, 3, ACTIVE));
        assertEquals(-2, MutantOps.ishr(-8, 2, ACTIVE));
        assertEquals(1073741822, MutantOps.iushr(-8, 2, ACTIVE));
    }

    @Test
    void integerArithmeticSwapsForTheOppositeOperation() {
        MutantSwitch.activate(ACTIVE);
        assertEquals(-1, MutantOps.iadd(3, 4, ACTIVE), "+ becomes -");
        assertEquals(7, MutantOps.isub(3, 4, ACTIVE), "- becomes +");
        assertEquals(2, MutantOps.imul(9, 4, ACTIVE), "* becomes /");
        assertEquals(36, MutantOps.idiv(9, 4, ACTIVE), "/ becomes *");
        assertEquals(36, MutantOps.irem(9, 4, ACTIVE), "% becomes *");
        assertEquals(0b1110, MutantOps.iand(0b0110, 0b1100, ACTIVE), "& becomes |");
        assertEquals(0b0100, MutantOps.ior(0b0110, 0b1100, ACTIVE), "| becomes &");
        assertEquals(0b0100, MutantOps.ixor(0b0110, 0b1100, ACTIVE), "^ becomes &");
        assertEquals(0, MutantOps.ishl(1, 3, ACTIVE), ">> replaces <<");
        assertEquals(-32, MutantOps.ishr(-8, 2, ACTIVE), "<< replaces >>");
        assertEquals(-32, MutantOps.iushr(-8, 2, ACTIVE), "<< replaces >>>");
    }

    @Test
    void anotherMutantBeingActiveLeavesThisSiteAlone() {
        MutantSwitch.activate(OTHER);
        assertEquals(7, MutantOps.iadd(3, 4, ACTIVE));
        assertEquals(12, MutantOps.imul(3, 4, ACTIVE));
        assertTrue(MutantOps.ifeq(0, MutantSwitch.NONE, ACTIVE));
    }

    // ------------------------------------------------------------ wider arithmetic

    @Test
    void longArithmeticIsUnchangedWhileNoMutantIsActive() {
        assertEquals(7L, MutantOps.ladd(3L, 4L, ACTIVE));
        assertEquals(-1L, MutantOps.lsub(3L, 4L, ACTIVE));
        assertEquals(12L, MutantOps.lmul(3L, 4L, ACTIVE));
        assertEquals(2L, MutantOps.ldiv(9L, 4L, ACTIVE));
        assertEquals(1L, MutantOps.lrem(9L, 4L, ACTIVE));
        assertEquals(0b0100L, MutantOps.land(0b0110L, 0b1100L, ACTIVE));
        assertEquals(0b1110L, MutantOps.lor(0b0110L, 0b1100L, ACTIVE));
        assertEquals(0b1010L, MutantOps.lxor(0b0110L, 0b1100L, ACTIVE));
        assertEquals(8L, MutantOps.lshl(1L, 3, ACTIVE));
        assertEquals(-2L, MutantOps.lshr(-8L, 2, ACTIVE));
        assertEquals(4611686018427387902L, MutantOps.lushr(-8L, 2, ACTIVE));
    }

    @Test
    void longArithmeticSwapsForTheOppositeOperation() {
        MutantSwitch.activate(ACTIVE);
        assertEquals(-1L, MutantOps.ladd(3L, 4L, ACTIVE));
        assertEquals(7L, MutantOps.lsub(3L, 4L, ACTIVE));
        assertEquals(2L, MutantOps.lmul(9L, 4L, ACTIVE));
        assertEquals(36L, MutantOps.ldiv(9L, 4L, ACTIVE));
        assertEquals(36L, MutantOps.lrem(9L, 4L, ACTIVE));
        assertEquals(0b1110L, MutantOps.land(0b0110L, 0b1100L, ACTIVE));
        assertEquals(0b0100L, MutantOps.lor(0b0110L, 0b1100L, ACTIVE));
        assertEquals(0b0100L, MutantOps.lxor(0b0110L, 0b1100L, ACTIVE));
        assertEquals(0L, MutantOps.lshl(1L, 3, ACTIVE));
        assertEquals(-32L, MutantOps.lshr(-8L, 2, ACTIVE));
        assertEquals(-32L, MutantOps.lushr(-8L, 2, ACTIVE));
    }

    @Test
    void floatAndDoubleArithmeticIsUnchangedWhileNoMutantIsActive() {
        assertEquals(7f, MutantOps.fadd(3f, 4f, ACTIVE));
        assertEquals(-1f, MutantOps.fsub(3f, 4f, ACTIVE));
        assertEquals(12f, MutantOps.fmul(3f, 4f, ACTIVE));
        assertEquals(2.25f, MutantOps.fdiv(9f, 4f, ACTIVE));
        assertEquals(1f, MutantOps.frem(9f, 4f, ACTIVE));
        assertEquals(7d, MutantOps.dadd(3d, 4d, ACTIVE));
        assertEquals(-1d, MutantOps.dsub(3d, 4d, ACTIVE));
        assertEquals(12d, MutantOps.dmul(3d, 4d, ACTIVE));
        assertEquals(2.25d, MutantOps.ddiv(9d, 4d, ACTIVE));
        assertEquals(1d, MutantOps.drem(9d, 4d, ACTIVE));
    }

    @Test
    void floatAndDoubleArithmeticSwapsForTheOppositeOperation() {
        MutantSwitch.activate(ACTIVE);
        assertEquals(-1f, MutantOps.fadd(3f, 4f, ACTIVE));
        assertEquals(7f, MutantOps.fsub(3f, 4f, ACTIVE));
        assertEquals(2.25f, MutantOps.fmul(9f, 4f, ACTIVE));
        assertEquals(36f, MutantOps.fdiv(9f, 4f, ACTIVE));
        assertEquals(36f, MutantOps.frem(9f, 4f, ACTIVE));
        assertEquals(-1d, MutantOps.dadd(3d, 4d, ACTIVE));
        assertEquals(7d, MutantOps.dsub(3d, 4d, ACTIVE));
        assertEquals(2.25d, MutantOps.dmul(9d, 4d, ACTIVE));
        assertEquals(36d, MutantOps.ddiv(9d, 4d, ACTIVE));
        assertEquals(36d, MutantOps.drem(9d, 4d, ACTIVE));
    }

    @Test
    void negationIsRemovedRatherThanInverted() {
        assertEquals(-5, MutantOps.ineg(5, ACTIVE));
        assertEquals(-5L, MutantOps.lneg(5L, ACTIVE));
        assertEquals(-5f, MutantOps.fneg(5f, ACTIVE));
        assertEquals(-5d, MutantOps.dneg(5d, ACTIVE));

        MutantSwitch.activate(ACTIVE);
        assertEquals(5, MutantOps.ineg(5, ACTIVE));
        assertEquals(5L, MutantOps.lneg(5L, ACTIVE));
        assertEquals(5f, MutantOps.fneg(5f, ACTIVE));
        assertEquals(5d, MutantOps.dneg(5d, ACTIVE));
    }

    @Test
    void incrementIsNegated() {
        assertEquals(6, MutantOps.increment(5, 1, ACTIVE));
        assertEquals(4, MutantOps.increment(5, -1, ACTIVE));

        MutantSwitch.activate(ACTIVE);
        assertEquals(4, MutantOps.increment(5, 1, ACTIVE), "i++ becomes i--");
        assertEquals(6, MutantOps.increment(5, -1, ACTIVE), "i-- becomes i++");
    }

    // ------------------------------------------------------------ conditionals

    /**
     * The zero-comparison branches, each with both of its mutants.
     *
     * <p>These carry two ids because two mutators apply to the same jump: one moves the boundary
     * and one negates the condition. Equality has no boundary to move, which is why ifeq and ifne
     * take the id but ignore it.
     */
    @Test
    void zeroComparisonsTakeTheOriginalConditionWhileNoMutantIsActive() {
        assertTrue(MutantOps.ifeq(0, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.ifeq(1, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.ifne(1, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.ifne(0, MutantSwitch.NONE, ACTIVE));

        assertTrue(MutantOps.iflt(-1, ACTIVE, OTHER));
        assertFalse(MutantOps.iflt(0, ACTIVE, OTHER));
        assertTrue(MutantOps.ifle(0, ACTIVE, OTHER));
        assertFalse(MutantOps.ifle(1, ACTIVE, OTHER));
        assertTrue(MutantOps.ifgt(1, ACTIVE, OTHER));
        assertFalse(MutantOps.ifgt(0, ACTIVE, OTHER));
        assertTrue(MutantOps.ifge(0, ACTIVE, OTHER));
        assertFalse(MutantOps.ifge(-1, ACTIVE, OTHER));
    }

    @Test
    void negatingAZeroComparisonInvertsIt() {
        MutantSwitch.activate(ACTIVE);
        assertFalse(MutantOps.ifeq(0, MutantSwitch.NONE, ACTIVE), "== 0 becomes != 0");
        assertTrue(MutantOps.ifeq(1, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.ifne(1, MutantSwitch.NONE, ACTIVE), "!= 0 becomes == 0");
        assertTrue(MutantOps.ifne(0, MutantSwitch.NONE, ACTIVE));

        assertTrue(MutantOps.iflt(0, MutantSwitch.NONE, ACTIVE), "< 0 becomes >= 0");
        assertTrue(MutantOps.ifle(1, MutantSwitch.NONE, ACTIVE), "<= 0 becomes > 0");
        assertTrue(MutantOps.ifgt(0, MutantSwitch.NONE, ACTIVE), "> 0 becomes <= 0");
        assertTrue(MutantOps.ifge(-1, MutantSwitch.NONE, ACTIVE), ">= 0 becomes < 0");
    }

    @Test
    void movingAZeroComparisonsBoundaryShiftsItByOne() {
        MutantSwitch.activate(ACTIVE);
        assertTrue(MutantOps.iflt(0, ACTIVE, OTHER), "< 0 becomes <= 0");
        assertFalse(MutantOps.ifle(0, ACTIVE, OTHER), "<= 0 becomes < 0");
        assertTrue(MutantOps.ifgt(0, ACTIVE, OTHER), "> 0 becomes >= 0, so zero now passes");
        assertFalse(MutantOps.ifge(0, ACTIVE, OTHER), ">= 0 becomes > 0, so zero now fails");
    }

    @Test
    void twoValueComparisonsTakeTheOriginalConditionWhileNoMutantIsActive() {
        assertTrue(MutantOps.icmpeq(1, 1, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.icmpeq(1, 2, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.icmpne(1, 2, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.icmpne(1, 1, MutantSwitch.NONE, ACTIVE));

        assertTrue(MutantOps.icmplt(1, 2, ACTIVE, OTHER));
        assertFalse(MutantOps.icmplt(2, 2, ACTIVE, OTHER));
        assertTrue(MutantOps.icmple(2, 2, ACTIVE, OTHER));
        assertFalse(MutantOps.icmple(3, 2, ACTIVE, OTHER));
        assertTrue(MutantOps.icmpgt(3, 2, ACTIVE, OTHER));
        assertFalse(MutantOps.icmpgt(2, 2, ACTIVE, OTHER));
        assertTrue(MutantOps.icmpge(2, 2, ACTIVE, OTHER));
        assertFalse(MutantOps.icmpge(1, 2, ACTIVE, OTHER));
    }

    @Test
    void negatingATwoValueComparisonInvertsIt() {
        MutantSwitch.activate(ACTIVE);
        assertFalse(MutantOps.icmpeq(1, 1, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.icmpne(1, 2, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.icmplt(2, 2, MutantSwitch.NONE, ACTIVE), "a < b becomes a >= b");
        assertTrue(MutantOps.icmple(3, 2, MutantSwitch.NONE, ACTIVE), "a <= b becomes a > b");
        assertTrue(MutantOps.icmpgt(2, 2, MutantSwitch.NONE, ACTIVE), "a > b becomes a <= b");
        assertTrue(MutantOps.icmpge(1, 2, MutantSwitch.NONE, ACTIVE), "a >= b becomes a < b");
    }

    @Test
    void movingATwoValueComparisonsBoundaryShiftsItByOne() {
        MutantSwitch.activate(ACTIVE);
        assertTrue(MutantOps.icmplt(2, 2, ACTIVE, OTHER), "a < b becomes a <= b");
        assertFalse(MutantOps.icmple(2, 2, ACTIVE, OTHER), "a <= b becomes a < b");
        assertTrue(MutantOps.icmpgt(2, 2, ACTIVE, OTHER), "a > b becomes a >= b, so equal now passes");
        assertFalse(MutantOps.icmpge(2, 2, ACTIVE, OTHER), "a >= b becomes a > b, so equal now fails");
    }

    @Test
    void referenceAndNullComparisonsAreNegatedAndHaveNoBoundary() {
        Object a = new Object();
        Object b = new Object();

        assertTrue(MutantOps.acmpeq(a, a, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.acmpeq(a, b, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.acmpne(a, b, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.isnull(null, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.isnull(a, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.isnonnull(a, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.isnonnull(null, MutantSwitch.NONE, ACTIVE));

        MutantSwitch.activate(ACTIVE);
        assertFalse(MutantOps.acmpeq(a, a, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.acmpeq(a, b, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.acmpne(a, b, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.isnull(null, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.isnull(a, MutantSwitch.NONE, ACTIVE));
        assertFalse(MutantOps.isnonnull(a, MutantSwitch.NONE, ACTIVE));
        assertTrue(MutantOps.isnonnull(null, MutantSwitch.NONE, ACTIVE));
    }

    /**
     * A conditional whose other mutator does not apply is compiled with NONE for that id, and
     * NONE must never fire.
     *
     * <p>This is the case the {@code != NONE} guards in every conditional op exist for. Without
     * them, a site where only one of the two mutators applies would take the other mutant's
     * branch as soon as nothing at all was active, because the unset switch is also NONE.
     */
    @Test
    void anInapplicableMutatorsIdNeverFires() {
        MutantSwitch.deactivate();
        assertEquals(MutantSwitch.NONE, MutantSwitch.active(),
                "the guards below are only meaningful because the idle switch is also NONE");

        assertTrue(MutantOps.iflt(-1, MutantSwitch.NONE, MutantSwitch.NONE),
                "neither mutant applies here, so the original condition must hold");
        assertTrue(MutantOps.icmplt(1, 2, MutantSwitch.NONE, MutantSwitch.NONE));
        assertTrue(MutantOps.ifeq(0, MutantSwitch.NONE, MutantSwitch.NONE));
        assertTrue(MutantOps.isnull(null, MutantSwitch.NONE, MutantSwitch.NONE));
        assertTrue(MutantOps.booleanReturn(true, MutantSwitch.NONE, MutantSwitch.NONE));
        assertFalse(MutantOps.booleanReturn(false, MutantSwitch.NONE, MutantSwitch.NONE));
    }

    // ------------------------------------------------------------ returns

    @Test
    void booleanReturnsCanBeForcedEitherWay() {
        assertTrue(MutantOps.booleanReturn(true, ACTIVE, OTHER));
        assertFalse(MutantOps.booleanReturn(false, ACTIVE, OTHER));

        MutantSwitch.activate(ACTIVE);
        assertTrue(MutantOps.booleanReturn(false, ACTIVE, OTHER), "forced true");

        MutantSwitch.activate(OTHER);
        assertFalse(MutantOps.booleanReturn(true, ACTIVE, OTHER), "forced false");
    }

    @Test
    void numericReturnsAreForcedToZero() {
        assertEquals(5, MutantOps.intReturn(5, ACTIVE));
        assertEquals(5L, MutantOps.longReturn(5L, ACTIVE));
        assertEquals(5f, MutantOps.floatReturn(5f, ACTIVE));
        assertEquals(5d, MutantOps.doubleReturn(5d, ACTIVE));

        MutantSwitch.activate(ACTIVE);
        assertEquals(0, MutantOps.intReturn(5, ACTIVE));
        assertEquals(0L, MutantOps.longReturn(5L, ACTIVE));
        assertEquals(0f, MutantOps.floatReturn(5f, ACTIVE));
        assertEquals(0d, MutantOps.doubleReturn(5d, ACTIVE));
    }

    @Test
    void referenceReturnsTakeTheEmptyValueTheCallerSupplied() {
        String value = "something";
        assertSame(value, MutantOps.emptyReturn(value, "", ACTIVE));

        MutantSwitch.activate(ACTIVE);
        assertEquals("", MutantOps.emptyReturn(value, "", ACTIVE));
        assertNull(MutantOps.emptyReturn(value, null, ACTIVE),
                "null is the empty value for a type that has no other");
    }
}
