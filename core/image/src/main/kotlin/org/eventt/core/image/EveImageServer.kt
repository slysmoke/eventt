package org.eventt.core.image

import okhttp3.Request
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.AppPaths
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * EVE Online Image Server integration.
 * Fetches renders, icons, portraits, etc. from images.evetech.net.
 */
object EveImageServer {
    private const val BASE_URL = "https://images.evetech.net"

    // Memory LRU cache
    private val memoryCache =
        object : LinkedHashMap<String, BufferedImage>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?): Boolean {
                return size > 200 // Keep last 200 images in memory
            }
        }

    // Disk cache: store raw bytes in a simple file-based cache
    private val diskCacheDir = java.io.File(AppPaths.appDataDir, "image-cache")

    enum class ImageCategory(
        val path: String,
    ) {
        TYPES("types"),
        CHARACTERS("characters"),
        CORPORATIONS("corporations"),
        ALLIANCES("alliances"),
        FACTIONS("factions"),
    }

    enum class ImageVariation(
        val path: String,
    ) {
        RENDER("render"),
        ICON("icon"),
        PORTRAIT("portrait"),
        BUST("bust"),
        LOGO("logo"),
    }

    /**
     * Build the image URL for a given entity.
     */
    fun buildImageUrl(
        category: ImageCategory,
        id: Int,
        variation: ImageVariation = ImageVariation.ICON,
        size: Int = 64,
    ): String = "$BASE_URL/${category.path}/$id/${variation.path}?size=$size"

    /**
     * Load an image (with memory + disk caching).
     */
    fun loadImage(
        category: ImageCategory,
        id: Int,
        variation: ImageVariation = ImageVariation.ICON,
        size: Int = 64,
    ): BufferedImage? {
        val cacheKey = "${category.path}_${id}_${variation.path}_$size"

        // Check memory cache
        synchronized(memoryCache) {
            memoryCache[cacheKey]?.let { return it }
        }

        // Check disk cache -- a null/failed decode means the cached bytes are bad (e.g. EVE's
        // image server once returned a non-image body that got cached verbatim), so the file is
        // dropped and treated as a cache miss instead of poisoning this icon forever.
        val diskFile = getDiskCacheFile(cacheKey)
        if (diskFile?.exists() == true) {
            val decoded =
                try {
                    ImageIO.read(diskFile)
                } catch (e: Exception) {
                    null
                }
            if (decoded != null) {
                synchronized(memoryCache) { memoryCache[cacheKey] = decoded }
                return decoded
            }
            diskFile.delete()
        }

        // Fetch from server
        val url = buildImageUrl(category, id, variation, size)
        return fetchAndCache(url, cacheKey)
    }

    /**
     * Get type icon (most common use case).
     */
    fun getTypeIcon(
        typeId: Int,
        size: Int = 32,
    ): BufferedImage? = loadImage(ImageCategory.TYPES, typeId, ImageVariation.ICON, size)

    /**
     * Get type render (for display).
     */
    fun getTypeRender(
        typeId: Int,
        size: Int = 256,
    ): BufferedImage? = loadImage(ImageCategory.TYPES, typeId, ImageVariation.RENDER, size)

    /**
     * Get character portrait.
     */
    fun getCharacterPortrait(
        characterId: Int,
        size: Int = 128,
    ): BufferedImage? = loadImage(ImageCategory.CHARACTERS, characterId, ImageVariation.PORTRAIT, size)

    /**
     * Get corporation logo.
     */
    fun getCorporationLogo(
        corporationId: Int,
        size: Int = 128,
    ): BufferedImage? = loadImage(ImageCategory.CORPORATIONS, corporationId, ImageVariation.LOGO, size)

    // ─── Internal ───────────────────────────────────────────────────────

    private fun fetchAndCache(
        url: String,
        cacheKey: String,
    ): BufferedImage? {
        return try {
            val client = EveHttpClient.getClient()
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val bytes = response.body.bytes()

                // Decode first -- only bytes that actually are an image get persisted, so a 200
                // response with a non-image body (error page, truncated transfer) doesn't get
                // cached forever and permanently break this icon.
                val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
                saveToDiskCache(cacheKey, bytes)
                synchronized(memoryCache) { memoryCache[cacheKey] = img }
                img
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getDiskCacheFile(cacheKey: String): java.io.File? {
        if (!diskCacheDir.exists()) diskCacheDir.mkdirs()
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = java.io.File(diskCacheDir, "$safeName.png")
        return if (file.exists()) file else null
    }

    private fun saveToDiskCache(
        cacheKey: String,
        bytes: ByteArray,
    ) {
        if (!diskCacheDir.exists()) diskCacheDir.mkdirs()
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = java.io.File(diskCacheDir, "$safeName.png")
        file.writeBytes(bytes)
    }
}
