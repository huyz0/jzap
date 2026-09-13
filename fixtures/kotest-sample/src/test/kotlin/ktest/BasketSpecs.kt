package ktest

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** One spec per style, because each builds its test tree differently. */
class SubtotalStringSpec : StringSpec({
    "adds prices together" {
        Basket().subtotal(listOf(1, 2, 3)) shouldBe 6
    }

    "an empty basket costs nothing" {
        Basket().subtotal(emptyList()) shouldBe 0
    }
})

class ShippingFunSpec : FunSpec({
    test("large baskets ship free") {
        Basket().shipping(100) shouldBe 0
    }

    test("small baskets pay shipping") {
        Basket().shipping(99) shouldBe 10
    }

    // Only one side of the boundary, so moving it is invisible.
    test("bulk orders get a discount") {
        Basket().bulkDiscount(20) shouldBe 5
    }
})

class EmptinessDescribeSpec : DescribeSpec({
    describe("an empty basket") {
        it("is empty") {
            Basket().isEmpty(emptyList()) shouldBe true
        }
    }

    describe("a full basket") {
        it("is not empty") {
            Basket().isEmpty(listOf(1)) shouldBe false
        }
    }
})

class SubtotalBehaviorSpec : BehaviorSpec({
    given("a basket with three items") {
        `when`("the subtotal is taken") {
            then("it is the sum of the prices") {
                Basket().subtotal(listOf(5, 5, 5)) shouldBe 15
            }
        }
    }
})
