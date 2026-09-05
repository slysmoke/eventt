package org.eventt.core.database

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eventt.core.model.CharacterModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppStateTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        TokenCrypto.keyFile = File(tempDir, "token.key")
    }

    @AfterEach
    fun cleanUp() {
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM characters")
                it.execute("DELETE FROM settings")
                it.execute("DELETE FROM corporations")
            }
        }
    }

    private fun character(id: Int) = CharacterModel(id = id, name = "Pilot $id", refreshToken = "r", accessToken = "a", tokenExpiry = 0)

    @Test
    fun `init with no characters and no saved setting selects nothing`() {
        AppState.init()

        AppState.selectedCharId.value.shouldBeNull()
    }

    @Test
    fun `init with characters but no saved setting selects the first one`() {
        CharacterDao.insert(character(1))
        CharacterDao.insert(character(2))

        AppState.init()

        AppState.selectedCharId.value shouldBe 1
    }

    @Test
    fun `init prefers the saved character id when it still exists`() {
        CharacterDao.insert(character(1))
        CharacterDao.insert(character(2))
        StaticDataDao.setSetting("app.selected_context", "char:2")

        AppState.init()

        AppState.selectedCharId.value shouldBe 2
    }

    @Test
    fun `init falls back to the first character when the saved id no longer exists`() {
        CharacterDao.insert(character(1))
        StaticDataDao.setSetting("app.selected_context", "char:999")

        AppState.init()

        AppState.selectedCharId.value shouldBe 1
    }

    @Test
    fun `selectCharacter updates both the state flow and the persisted setting`() {
        CharacterDao.insert(character(1))
        CharacterDao.insert(character(2))
        AppState.init()

        AppState.selectCharacter(2)

        AppState.selectedCharId.value shouldBe 2
        StaticDataDao.getSetting("app.selected_context") shouldBe "char:2"
    }

    @Test
    fun `selectCorporation sets a corporation context with the acting character as selectedCharId`() {
        val char1 = character(1).copy(corporationId = 100, corporationName = "Test Corp")
        CharacterDao.insert(char1)
        AppState.init()

        AppState.selectCorporation(100, "Test Corp", 1)

        AppState.selectedCharId.value shouldBe 1
        (AppState.selectedContext.value as ViewContext.Corporation).corporationId shouldBe 100
        StaticDataDao.getSetting("app.selected_context") shouldBe "corp:100:1"
    }

    @Test
    fun `init restores a persisted corporation context when the acting character still belongs to it`() {
        val char1 = character(1).copy(corporationId = 100, corporationName = "Test Corp")
        CharacterDao.insert(char1)
        CorporationDao.insert(id = 100, name = "Test Corp", ticker = "", allianceId = null)
        CorporationDao.track(100)
        StaticDataDao.setSetting("app.selected_context", "corp:100:1")

        AppState.init()

        AppState.selectedCharId.value shouldBe 1
        (AppState.selectedContext.value as ViewContext.Corporation).corporationId shouldBe 100
    }

    @Test
    fun `init does not restore a persisted corporation context once it's untracked`() {
        val char1 = character(1).copy(corporationId = 100, corporationName = "Test Corp")
        CharacterDao.insert(char1)
        StaticDataDao.setSetting("app.selected_context", "corp:100:1")
        // Deliberately not tracking corp 100 -- simulates a corp that's since been untracked.

        AppState.init()

        AppState.selectedContext.value shouldBe ViewContext.Character(1)
    }

    @Test
    fun `refreshCharacters drops the current corp selection once it's untracked`() {
        val char1 = character(1).copy(corporationId = 100, corporationName = "Test Corp")
        val char2 = character(2)
        CharacterDao.insert(char1)
        CharacterDao.insert(char2)
        CorporationDao.insert(id = 100, name = "Test Corp", ticker = "", allianceId = null)
        CorporationDao.track(100)
        AppState.init()
        AppState.selectCorporation(100, "Test Corp", 1)

        CorporationDao.untrack(100)
        AppState.refreshCharacters()

        AppState.selectedContext.value shouldBe ViewContext.Character(1)
    }

    @Test
    fun `refreshCharacters falls back when the acting character behind a corp selection is removed`() {
        val char1 = character(1).copy(corporationId = 100, corporationName = "Test Corp")
        val char2 = character(2)
        CharacterDao.insert(char1)
        CharacterDao.insert(char2)
        CorporationDao.insert(id = 100, name = "Test Corp", ticker = "", allianceId = null)
        CorporationDao.track(100)
        AppState.init()
        AppState.selectCorporation(100, "Test Corp", 1)
        CharacterDao.delete(1)

        AppState.refreshCharacters()

        AppState.selectedContext.value shouldBe ViewContext.Character(2)
    }

    @Test
    fun `refreshCharacters keeps the current selection if it's still valid`() {
        CharacterDao.insert(character(1))
        CharacterDao.insert(character(2))
        AppState.init()
        AppState.selectCharacter(2)

        AppState.refreshCharacters()

        AppState.selectedCharId.value shouldBe 2
    }

    @Test
    fun `refreshCharacters falls back to another character if the current one was removed`() {
        CharacterDao.insert(character(1))
        CharacterDao.insert(character(2))
        AppState.init()
        AppState.selectCharacter(2)
        CharacterDao.delete(2)

        AppState.refreshCharacters()

        AppState.selectedCharId.value shouldBe 1
    }

    @Test
    fun `refreshCharacters selects nothing once the last character is removed`() {
        CharacterDao.insert(character(1))
        AppState.init()
        CharacterDao.delete(1)

        AppState.refreshCharacters()

        AppState.selectedCharId.value.shouldBeNull()
    }
}
