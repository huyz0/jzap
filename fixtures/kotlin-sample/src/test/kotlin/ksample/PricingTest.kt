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
        assertEquals(3, applyTwice(1) { it + 1 })
    }

    @Test
    fun `adds a surcharge before checking out`() {
        assertEquals(30, checkoutOnline(10))
        assertEquals(45, checkoutInStore(10))
    }

    @Test
    fun `a basket sums what is added to it`() {
        val basket = Basket(limit = 100)
        assertTrue(basket.isEmpty)
        assertEquals(0, basket.total)

        assertEquals(14, basket.addLabelled("book", 10))
        assertEquals(10, basket.total)
        assertFalse(basket.isEmpty)
    }

    @Test
    fun `a basket is within its limit exactly at the limit`() {
        val basket = Basket(limit = 10)
        basket.addLabelled("a", 10)

        // At the boundary, so moving <= to < is detected rather than surviving.
        assertTrue(basket.withinLimit())

        basket.addLabelled("b", 1)
        assertFalse(basket.withinLimit())

        basket.limit = 100
        assertTrue(basket.withinLimit())
    }

    @Test
    fun `copying an order replaces only what was named`() {
        val order = Order(id = "a1", amount = 30)
        val copy = renamed(order, "b2")

        assertEquals("b2", copy.id)
        assertEquals(30, copy.amount)
        assertEquals("GBP", copy.currency)
    }
}
