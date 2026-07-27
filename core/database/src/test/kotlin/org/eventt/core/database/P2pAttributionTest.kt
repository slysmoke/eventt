package org.eventt.core.database

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class P2pAttributionTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @AfterEach
    fun cleanUp() {
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM settings") } }
    }

    @Test
    fun `Character attribution round-trips through encode and decode`() {
        val decoded = TransactionAttribution.decode(TransactionAttribution.Character(95465499).encode())

        decoded shouldBe TransactionAttribution.Character(95465499)
    }

    @Test
    fun `Corporation attribution round-trips through encode and decode`() {
        val decoded = TransactionAttribution.decode(TransactionAttribution.Corporation(98765432).encode())

        decoded shouldBe TransactionAttribution.Corporation(98765432)
    }

    @Test
    fun `decode returns null for blank or malformed input`() {
        TransactionAttribution.decode(null).shouldBeNull()
        TransactionAttribution.decode("").shouldBeNull()
        TransactionAttribution.decode("garbage").shouldBeNull()
        TransactionAttribution.decode("char:notanumber").shouldBeNull()
    }

    @Test
    fun `P2pAttributionDefault persists across get calls until changed`() {
        P2pAttributionDefault.get().shouldBeNull()

        P2pAttributionDefault.set(TransactionAttribution.Corporation(42))

        P2pAttributionDefault.get() shouldBe TransactionAttribution.Corporation(42)
    }
}
