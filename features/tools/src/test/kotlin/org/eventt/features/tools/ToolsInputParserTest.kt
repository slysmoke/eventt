package org.eventt.features.tools

import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.StaticTypeModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ToolsInputParserTest {
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
            createStatement().use { stmt -> stmt.execute("DELETE FROM static_types") }
        }
    }

    // ── parse() ──────────────────────────────────────────────────────────────

    @Test
    fun `well-formed lines parse into name and quantity`() {
        val (items, warnings) = ToolsInputParser.parse("Tritanium\t1000\nPyerite\t500")
        items shouldBe listOf(ParsedItemLine("Tritanium", 1000), ParsedItemLine("Pyerite", 500))
        warnings.isEmpty() shouldBe true
    }

    @Test
    fun `a line with no tab becomes a warning, not a silent drop`() {
        val (items, warnings) = ToolsInputParser.parse("Tritanium 1000\nPyerite\t500")
        items shouldBe listOf(ParsedItemLine("Pyerite", 500))
        warnings.size shouldBe 1
        warnings.first().lineText shouldBe "Tritanium 1000"
    }

    @Test
    fun `a non-numeric quantity becomes a warning, not a silent drop`() {
        val (items, warnings) = ToolsInputParser.parse("Tritanium\tabc")
        items.isEmpty() shouldBe true
        warnings.single().reason.contains("not a whole number") shouldBe true
    }

    @Test
    fun `blank lines are skipped without generating a warning`() {
        val (items, warnings) = ToolsInputParser.parse("Tritanium\t1000\n\n\nPyerite\t500")
        items.size shouldBe 2
        warnings.isEmpty() shouldBe true
    }

    @Test
    fun `comma thousands separators in quantity are tolerated`() {
        val (items, _) = ToolsInputParser.parse("Liquid Ozone\t2,000,000")
        items.single().quantity shouldBe 2_000_000
    }

    // ── resolve() ─────────────────────────────────────────────────────────────

    @Test
    fun `an unresolved item name becomes a warning, not an exception`() {
        StaticDataDao.insertType(StaticTypeModel(typeId = 34, name = "Tritanium", groupId = 1, categoryId = 4, volume = 0.01))

        val parsed = listOf(ParsedItemLine("Tritanium", 100), ParsedItemLine("Not A Real Item", 5))
        val (resolved, warnings) = ToolsInputParser.resolve(parsed) { it.volume }

        resolved.size shouldBe 1
        resolved.single().typeId shouldBe 34
        warnings.size shouldBe 1
        warnings.single().reason shouldBe "item name not found in static data"
    }

    @Test
    fun `resolve is case-insensitive and applies the given volume selector`() {
        StaticDataDao.insertType(
            StaticTypeModel(typeId = 645, name = "Dominix", groupId = 1, categoryId = 6, volume = 500000.0, packagedVolume = 3750.0),
        )
        val parsed = listOf(ParsedItemLine("dominix", 2))
        val (resolved, warnings) = ToolsInputParser.resolve(parsed) { it.packagedVolume }

        warnings.isEmpty() shouldBe true
        resolved.single().let {
            it.name shouldBe "Dominix"
            it.unitVolume shouldBe 3750.0
            it.categoryId shouldBe 6
        }
    }
}
