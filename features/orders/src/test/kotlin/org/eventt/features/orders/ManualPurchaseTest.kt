package org.eventt.features.orders

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ManualPurchaseTest {
    @Test
    fun `parseIskInput accepts grouped and decimal-comma prices`() {
        parseIskInput("1 234 567") shouldBe 1_234_567.0
        parseIskInput("1,234,567.89") shouldBe 1_234_567.89
        parseIskInput("12345,5") shouldBe 12_345.5
        parseIskInput("1,234") shouldBe 1234.0
        parseIskInput("0") shouldBe null
        parseIskInput("abc") shouldBe null
    }
}
