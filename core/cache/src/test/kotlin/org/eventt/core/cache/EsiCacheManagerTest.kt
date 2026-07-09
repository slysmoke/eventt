package org.eventt.core.cache

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant

class EsiCacheManagerTest {
    companion object {
        // DatabaseManager is a process-wide singleton that otherwise lazily self-initializes
        // against the *real* ~/.eve-trader/eve_trader.db on first use — without this, these
        // tests would silently read/write the developer's actual production database.
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.initialize(":memory:")
        }
    }

    @Test
    fun `parseExpiresHeader parses a valid RFC1123 date`() {
        val epoch = EsiCacheManager.parseExpiresHeader("Tue, 03 Jun 2025 11:05:30 GMT")

        epoch shouldBe Instant.parse("2025-06-03T11:05:30Z").toEpochMilli()
    }

    @Test
    fun `parseExpiresHeader falls back to now plus the default TTL on unparseable input`() {
        val before = System.currentTimeMillis()

        val result = EsiCacheManager.parseExpiresHeader("not a date")

        result shouldBeGreaterThan before
    }

    @Test
    fun `parseCacheControl turns max-age into an absolute expiry`() {
        val before = System.currentTimeMillis()

        val result = EsiCacheManager.parseCacheControl("public, max-age=300")

        result.shouldNotBeNull()
        (result - before) shouldBeGreaterThan 299_000L
    }

    @Test
    fun `parseCacheControl returns null when there's no max-age`() {
        EsiCacheManager.parseCacheControl("no-cache").shouldBeNull()
    }

    @Test
    fun `an uncached endpoint is a MISS`() {
        val result = EsiCacheManager.get("/test/never-cached/", mapOf("k" to "v"))

        result.state shouldBe CacheState.MISS
    }

    @Test
    fun `save then get round-trips as FRESH with the same data`() {
        val endpoint = "/test/roundtrip/"
        val params = mapOf("type_id" to "12345")

        EsiCacheManager.save(endpoint, params, """{"hello":"world"}""", System.currentTimeMillis() + 60_000)
        val result = EsiCacheManager.get(endpoint, params)

        result.state shouldBe CacheState.FRESH
        result.data shouldBe """{"hello":"world"}"""
    }

    @Test
    fun `an expired entry reads back as STALE, not MISS`() {
        val endpoint = "/test/expired/"

        EsiCacheManager.save(endpoint, null, "stale-body", System.currentTimeMillis() - 1_000)
        val result = EsiCacheManager.get(endpoint, null)

        result.state shouldBe CacheState.STALE
        result.data shouldBe "stale-body"
    }

    @Test
    fun `invalidateEndpoint forces a subsequent get to MISS, in both the memory and SQLite layers`() {
        val endpoint = "/test/invalidate/"
        EsiCacheManager.save(endpoint, mapOf("page" to "1"), "page-1-body", System.currentTimeMillis() + 60_000)
        EsiCacheManager.save(endpoint, null, "merged-body", System.currentTimeMillis() + 60_000)

        EsiCacheManager.invalidateEndpoint(endpoint)

        EsiCacheManager.get(endpoint, mapOf("page" to "1")).state shouldBe CacheState.MISS
        EsiCacheManager.get(endpoint, null).state shouldBe CacheState.MISS
    }

    @Test
    fun `invalidateEndpoint leaves other endpoints' cached entries untouched`() {
        EsiCacheManager.save("/test/keep-me/", null, "still-here", System.currentTimeMillis() + 60_000)

        EsiCacheManager.invalidateEndpoint("/test/unrelated/")

        EsiCacheManager.get("/test/keep-me/", null).state shouldBe CacheState.FRESH
    }

    @Test
    fun `cleanupExpired keeps recently-expired rows but purges rows expired over a day ago`() {
        val recentlyExpired = "/test/cleanup-recent/"
        val longExpired = "/test/cleanup-old/"

        EsiCacheManager.save(recentlyExpired, null, "recent-body", System.currentTimeMillis() - 60_000)
        EsiCacheManager.save(longExpired, null, "old-body", System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        EsiCacheManager.cleanupExpired()

        EsiCacheManager.get(recentlyExpired, null).state shouldBe CacheState.STALE
        EsiCacheManager.get(longExpired, null).state shouldBe CacheState.MISS
    }
}
