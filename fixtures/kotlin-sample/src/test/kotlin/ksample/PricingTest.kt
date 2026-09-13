package ksample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PricingTest {

    private val pricing = Pricing(floor = 0)

    @Test
    fun `applies a discount`() {
        assertEquals(90, pricing.applyDiscount(100, 10))
        assertEquals(50, pricing.applyDiscount(100, 90))
    }

    @Test
    fun `detects free items`() {
        assertTrue(pricing.isFree(0))
        assertFalse(pricing.isFree(5))
    }

    @Test
    fun `describes prices`() {
        assertEquals("free", pricing.describe(0))
        assertEquals("paid", pricing.describe(10))
    }

    @Test
    fun `falls back when the name is null`() {
        assertEquals("unnamed", pricing.label(null))
        assertEquals("trimmed", pricing.label("  trimmed  "))
    }

    @Test
    fun `sums a list`() {
        assertEquals(6, pricing.totalOf(listOf(1, 2, 3)))
    }

    @Test
    fun `applies a function twice`() {
        assertEquals(4, applyTwice(1) { it + 1 })
    }

    @Test
    fun `adds a surcharge before checking out`() {
        assertEquals(30, checkoutOnline(10))
        assertEquals(45, checkoutInStore(10))
    }
}
