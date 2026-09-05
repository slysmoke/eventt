package org.eventt.core.database

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eventt.core.model.CharacterModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CharacterDaoTest {
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
        // Never the real ~/.eve-trader/token.key.
        TokenCrypto.keyFile = File(tempDir, "token.key")
    }

    @AfterEach
    fun cleanUp() {
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM characters")
                it.execute("DELETE FROM corporations")
            }
        }
    }

    private fun character(
        id: Int = 1,
        corporationId: Int? = 200,
    ) = CharacterModel(
        id = id,
        name = "Test Pilot",
        refreshToken = "refresh-secret",
        accessToken = "access-secret",
        tokenExpiry = 1_700_000_000_000L,
        corporationId = corporationId,
        corporationName = "Test Corp",
    )

    @Test
    fun `insert then getById round-trips a character, with tokens transparently decrypted`() {
        CharacterDao.insert(character())

        val loaded = CharacterDao.getById(1).shouldNotBeNull()

        loaded.name shouldBe "Test Pilot"
        loaded.refreshToken shouldBe "refresh-secret"
        loaded.accessToken shouldBe "access-secret"
        loaded.corporationId shouldBe 200
    }

    @Test
    fun `getById returns null for an unknown character`() {
        CharacterDao.getById(999).shouldBeNull()
    }

    @Test
    fun `insert is idempotent (INSERT OR REPLACE) and getAll is sorted by name`() {
        CharacterDao.insert(character(id = 1).copy(name = "Zeta"))
        CharacterDao.insert(character(id = 2).copy(name = "Alpha"))
        CharacterDao.insert(character(id = 1).copy(name = "Zeta Updated"))

        val all = CharacterDao.getAll()

        all.map { it.name } shouldBe listOf("Alpha", "Zeta Updated")
    }

    @Test
    fun `updateToken changes only the access token and expiry, not the refresh token`() {
        CharacterDao.insert(character())

        CharacterDao.updateToken(1, accessToken = "new-access", tokenExpiry = 42L)

        val loaded = CharacterDao.getById(1).shouldNotBeNull()
        loaded.accessToken shouldBe "new-access"
        loaded.tokenExpiry shouldBe 42L
        loaded.refreshToken shouldBe "refresh-secret"
    }

    @Test
    fun `updateRefreshToken changes only the refresh token`() {
        CharacterDao.insert(character())

        CharacterDao.updateRefreshToken(1, refreshToken = "new-refresh")

        CharacterDao.getById(1)?.refreshToken shouldBe "new-refresh"
    }

    @Test
    fun `getTokenExpiry and getAccessToken read back what was stored`() {
        CharacterDao.insert(character())

        CharacterDao.getTokenExpiry(1) shouldBe 1_700_000_000_000L
        CharacterDao.getAccessToken(1) shouldBe "access-secret"
    }

    @Test
    fun `getTokenExpiry for an unknown character is zero, not an error`() {
        CharacterDao.getTokenExpiry(999) shouldBe 0L
    }

    @Test
    fun `delete removes the character`() {
        CharacterDao.insert(character())

        CharacterDao.delete(1)

        CharacterDao.getById(1).shouldBeNull()
    }

    @Test
    fun `a null corporationId round-trips as null, not zero`() {
        CharacterDao.insert(character(corporationId = null))

        CharacterDao.getById(1)?.corporationId.shouldBeNull()
    }

    @Test
    fun `delete also drops the corporation once no character remains in it`() {
        CorporationDao.insert(id = 200, name = "Test Corp", ticker = "TEST", allianceId = null)
        CharacterDao.insert(character(id = 1))

        CharacterDao.delete(1)

        CorporationDao.getAll().any { it["id"] == 200 } shouldBe false
    }

    @Test
    fun `delete keeps the corporation while another character still belongs to it`() {
        CorporationDao.insert(id = 200, name = "Test Corp", ticker = "TEST", allianceId = null)
        CharacterDao.insert(character(id = 1))
        CharacterDao.insert(character(id = 2))

        CharacterDao.delete(1)

        CorporationDao.getAll().any { it["id"] == 200 } shouldBe true
    }

    @Test
    fun `track then untrack round-trips through getTrackedIds`() {
        CorporationDao.insert(id = 200, name = "Test Corp", ticker = "TEST", allianceId = null)

        CorporationDao.track(200)
        CorporationDao.getTrackedIds() shouldBe setOf(200)

        CorporationDao.untrack(200)
        CorporationDao.getTrackedIds() shouldBe emptySet()
    }

    @Test
    fun `pruning the last member's corporation also drops its tracked_corporations row`() {
        CorporationDao.insert(id = 200, name = "Test Corp", ticker = "TEST", allianceId = null)
        CharacterDao.insert(character(id = 1))
        CorporationDao.track(200)

        CharacterDao.delete(1)

        CorporationDao.getTrackedIds() shouldBe emptySet()
    }

    @Test
    fun `actingPairsForTracked only returns tracked corps, one acting character each`() {
        CorporationDao.insert(id = 200, name = "Tracked Corp", ticker = "TRK", allianceId = null)
        CorporationDao.insert(id = 300, name = "Untracked Corp", ticker = "UNT", allianceId = null)
        val trackedMember = character(id = 1, corporationId = 200)
        CharacterDao.insert(trackedMember)
        CharacterDao.insert(character(id = 2, corporationId = 300))
        CorporationDao.track(200)

        val pairs = CorporationDao.actingPairsForTracked(CharacterDao.getAll())

        pairs shouldBe listOf(200 to 1)
    }
}
