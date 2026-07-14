package org.eventt.core.database

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eventt.core.model.ContractItemModel
import org.eventt.core.model.ContractModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ContractDaoTest {
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
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM contracts")
                it.execute("DELETE FROM contract_items")
            }
        }
    }

    private fun contract(
        id: Int,
        status: String = "outstanding",
        characterId: Int? = 1,
        dateIssued: String = "2024-01-01",
    ) = ContractModel(
        contractId = id,
        issuerId = 100,
        issuerCorpId = 200,
        assigneeId = 0,
        acceptorId = 0,
        startStationId = 60003760L,
        endStationId = 60003760L,
        type = "item_exchange",
        status = status,
        dateIssued = dateIssued,
        dateExpired = "2024-02-01",
        characterId = characterId,
        corporationId = null,
    )

    @Test
    fun `upsert then getAll round-trips a contract`() {
        ContractDao.upsert(contract(1))

        ContractDao.getAll(characterId = 1).single().contractId shouldBe 1
    }

    @Test
    fun `upsert replaces an existing contract with the same id`() {
        ContractDao.upsert(contract(1, status = "outstanding"))
        ContractDao.upsert(contract(1, status = "finished"))

        ContractDao.getAll(characterId = 1).single().status shouldBe "finished"
    }

    @Test
    fun `bulkUpsert inserts every contract`() {
        ContractDao.bulkUpsert(listOf(contract(1), contract(2), contract(3)))

        ContractDao.getAll(characterId = 1) shouldHaveSize 3
    }

    @Test
    fun `upsert converts UTC ESI contract dates to the local time zone`() {
        val utcIssued = "2026-07-11T23:45:00Z"
        val expectedLocal =
            Instant.parse(utcIssued).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        ContractDao.upsert(contract(1, dateIssued = utcIssued))

        ContractDao.getAll(characterId = 1).single().dateIssued shouldBe expectedLocal
    }

    @Test
    fun `getAll orders by date issued, newest first`() {
        ContractDao.upsert(contract(1, dateIssued = "2024-01-01"))
        ContractDao.upsert(contract(2, dateIssued = "2024-03-01"))
        ContractDao.upsert(contract(3, dateIssued = "2024-02-01"))

        ContractDao.getAll(characterId = 1).map { it.contractId } shouldBe listOf(2, 3, 1)
    }

    @Test
    fun `getByStatus filters within the character's contracts`() {
        ContractDao.upsert(contract(1, status = "outstanding"))
        ContractDao.upsert(contract(2, status = "finished"))
        ContractDao.upsert(contract(3, status = "outstanding"))

        ContractDao.getByStatus("outstanding", characterId = 1).map { it.contractId }.toSet() shouldBe setOf(1, 3)
    }

    @Test
    fun `getAll with no matching character returns an empty list`() {
        ContractDao.getAll(characterId = 999).shouldBeEmpty()
    }

    @Test
    fun `insertContractItem then getItemsForContract round-trips the items for one contract`() {
        ContractDao.upsert(contract(1))
        ContractDao.insertContractItem(
            ContractItemModel(
                contractId = 1,
                recordId = 1,
                typeId = 34,
                typeName = "Tritanium",
                quantity = 100,
                rawQuantity = 100,
                isIncluded = true,
                isSingleton = false,
            ),
        )
        ContractDao.insertContractItem(
            ContractItemModel(
                contractId = 1,
                recordId = 2,
                typeId = 35,
                typeName = "Pyerite",
                quantity = 50,
                rawQuantity = 50,
                isIncluded = true,
                isSingleton = false,
            ),
        )

        val items = ContractDao.getItemsForContract(1)

        items.map { it.typeName }.toSet() shouldBe setOf("Tritanium", "Pyerite")
    }
}
