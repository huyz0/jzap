package ksample

/**
 * Ordinary Kotlin, written the way Kotlin is written. Every construct here compiles to bytecode
 * containing something javac would never emit, which is where junk mutants come from.
 */
class Pricing(private val floor: Int) {

    fun applyDiscount(price: Int, percent: Int): Int {
        val capped = if (percent > 50) 50 else percent
        return maxOf(floor, price - price * capped / 100)
    }

    fun isFree(price: Int): Boolean = price == 0

    fun describe(price: Int): String = when {
        price < 0 -> "invalid"
        price == 0 -> "free"
        else -> "paid"
    }

    fun label(name: String?): String = name?.trim() ?: "unnamed"

    fun totalOf(prices: List<Int>): Int {
        var total = 0
        for (price in prices) {
            total += price
        }
        return total
    }
}

data class Order(val id: String, val amount: Int)

/**
 * An inline function. kotlinc emits a real method here for Java callers to use, and also copies
 * the body into every Kotlin call site. So the same source line exists in the compiled output
 * three times over: once in a method that Kotlin callers never execute, and once inside each
 * caller, under synthetic line numbers past the end of this file.
 */
inline fun withSurcharge(base: Int, block: (Int) -> Int): Int {
    val surcharged = base + 5
    return block(surcharged)
}

/** Two call sites, so the inlined body appears twice in the compiled output. */
fun checkoutOnline(base: Int): Int = withSurcharge(base) { it * 2 }

fun checkoutInStore(base: Int): Int = withSurcharge(base) { it * 3 }

fun applyTwice(value: Int, f: (Int) -> Int): Int = f(f(value))
