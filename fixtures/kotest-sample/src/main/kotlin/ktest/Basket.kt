package ktest

/** Production code for the Kotest fixture, deliberately small and hand-analysable. */
class Basket {

    fun subtotal(prices: List<Int>): Int {
        var total = 0
        for (price in prices) {
            total += price
        }
        return total
    }

    fun shipping(subtotal: Int): Int = if (subtotal >= 100) 0 else 10

    fun isEmpty(prices: List<Int>): Boolean = prices.isEmpty()

    /** Deliberately under-tested: the boundary at 10 is never exercised. */
    fun bulkDiscount(quantity: Int): Int = if (quantity > 10) 5 else 0
}
