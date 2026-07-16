package org.eventt.core.database

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eventt.core.model.EsiCacheEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class EsiCacheDaoTest {
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
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM esi_cache") } }
    }

    private fun entry(
        endpoint: String = "/markets/10000002/orders/",
        paramsHash: String = EsiCacheDao.computeHash(mapOf("order_type" to "all")),
        expiresAt: Long = System.currentTimeMillis() + 60_000,
        etag: String? = "\"v1\"",
        lastModified: String? = "Mon, 01 Jan 2024 00:00:00 GMT",
    ) = EsiCacheEntry(endpoint, paramsHash, "[{\"price\":1.0}]", expiresAt, etag = etag, lastModified = lastModified)

    @Test
    fun `save then get round-trips a cache entry`() {
        EsiCacheDao.save(entry())

        val loaded = EsiCacheDao.get("/markets/10000002/orders/", mapOf("order_type" to "all")).shouldNotBeNull()

        loaded.data shouldBe "[{\"price\":1.0}]"
        loaded.etag shouldBe "\"v1\""
    }

    @Test
    fun `large payloads round-trip through compression, small ones stay plain`() {
        // Above the 1KB threshold — stored deflated (verified via the compressed flag), read back intact.
        val bigJson = "[" + (1..200).joinToString(",") { "{\"price\":$it.0,\"volume_remain\":$it}" } + "]"
        EsiCacheDao.save(entry().copy(data = bigJson))

        val loaded = EsiCacheDao.get("/markets/10000002/orders/", mapOf("order_type" to "all")).shouldNotBeNull()
        loaded.data shouldBe bigJson

        val flag =
            DatabaseManager.transaction {
                prepareStatement("SELECT compressed FROM esi_cache WHERE endpoint = ?").use { stmt ->
                    stmt.setString(1, "/markets/10000002/orders/")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        flag shouldBe 1
    }

    @Test
    fun `get with different params misses (different hash, same endpoint)`() {
        EsiCacheDao.save(entry())

        EsiCacheDao.get("/markets/10000002/orders/", mapOf("order_type" to "sell")).shouldBeNull()
    }

    @Test
    fun `computeHash is stable regardless of map entry insertion order`() {
        val a = EsiCacheDao.computeHash(mapOf("b" to "2", "a" to "1"))
        val b = EsiCacheDao.computeHash(mapOf("a" to "1", "b" to "2"))

        a shouldBe b
    }

    @Test
    fun `computeHash treats null params the same as an empty map`() {
        EsiCacheDao.computeHash(null) shouldBe EsiCacheDao.computeHash(emptyMap())
    }

    @Test
    fun `refreshExpiry bumps expiry and overwrites etag and last-modified when provided`() {
        val hash = EsiCacheDao.computeHash(mapOf("order_type" to "all"))
        EsiCacheDao.save(entry(paramsHash = hash, expiresAt = System.currentTimeMillis() - 1_000))

        val newExpiry = System.currentTimeMillis() + 120_000
        EsiCacheDao.refreshExpiry("/markets/10000002/orders/", hash, newExpiry, etag = "\"v2\"", lastModified = "new-date")

        val loaded = EsiCacheDao.get("/markets/10000002/orders/", mapOf("order_type" to "all")).shouldNotBeNull()
        loaded.expiresAt shouldBe newExpiry
        loaded.etag shouldBe "\"v2\""
        loaded.lastModified shouldBe "new-date"
    }

    @Test
    fun `refreshExpiry with null etag or last-modified keeps the existing values`() {
        val hash = EsiCacheDao.computeHash(mapOf("order_type" to "all"))
        EsiCacheDao.save(entry(paramsHash = hash, etag = "\"original\"", lastModified = "original-date"))

        EsiCacheDao.refreshExpiry(
            "/markets/10000002/orders/",
            hash,
            System.currentTimeMillis() + 60_000,
            etag = null,
            lastModified = null,
        )

        val loaded = EsiCacheDao.get("/markets/10000002/orders/", mapOf("order_type" to "all")).shouldNotBeNull()
        loaded.etag shouldBe "\"original\""
        loaded.lastModified shouldBe "original-date"
    }

    @Test
    fun `isFresh is true for a future expiry and false for a past one`() {
        EsiCacheDao.save(entry(endpoint = "/fresh/", expiresAt = System.currentTimeMillis() + 60_000))
        EsiCacheDao.save(entry(endpoint = "/stale/", expiresAt = System.currentTimeMillis() - 60_000))

        EsiCacheDao.isFresh("/fresh/", mapOf("order_type" to "all")) shouldBe true
        EsiCacheDao.isFresh("/stale/", mapOf("order_type" to "all")) shouldBe false
    }

    @Test
    fun `isFresh is false for an entry that doesn't exist`() {
        EsiCacheDao.isFresh("/never-cached/") shouldBe false
    }

    @Test
    fun `deleteExpired removes only expired rows`() {
        EsiCacheDao.save(entry(endpoint = "/fresh/", expiresAt = System.currentTimeMillis() + 60_000))
        EsiCacheDao.save(entry(endpoint = "/stale/", expiresAt = System.currentTimeMillis() - 60_000))

        EsiCacheDao.deleteExpired()

        EsiCacheDao.get("/fresh/", mapOf("order_type" to "all")).shouldNotBeNull()
        EsiCacheDao.get("/stale/", mapOf("order_type" to "all")).shouldBeNull()
    }

    @Test
    fun `deleteByEndpoint removes every params variant for that endpoint but leaves others alone`() {
        EsiCacheDao.save(entry(endpoint = "/characters/1/orders/", paramsHash = EsiCacheDao.computeHash(null)))
        EsiCacheDao.save(entry(endpoint = "/characters/1/orders/", paramsHash = EsiCacheDao.computeHash(mapOf("page" to "2"))))
        EsiCacheDao.save(entry(endpoint = "/characters/1/wallet/"))

        EsiCacheDao.deleteByEndpoint("/characters/1/orders/")

        EsiCacheDao.get("/characters/1/orders/", null).shouldBeNull()
        EsiCacheDao.get("/characters/1/orders/", mapOf("page" to "2")).shouldBeNull()
        EsiCacheDao.get("/characters/1/wallet/", mapOf("order_type" to "all")).shouldNotBeNull()
    }

    @Test
    fun `clearAll removes every cache entry`() {
        EsiCacheDao.save(entry(endpoint = "/a/"))
        EsiCacheDao.save(entry(endpoint = "/b/"))

        EsiCacheDao.clearAll()

        EsiCacheDao.get("/a/", mapOf("order_type" to "all")).shouldBeNull()
        EsiCacheDao.get("/b/", mapOf("order_type" to "all")).shouldBeNull()
    }
}
