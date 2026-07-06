package org.eventt.update

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eventt.AppVersion
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class UpdateInfo(
    val version: String,
    val tagName: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val releaseNotes: String,
)

sealed class UpdateProgress {
    object Idle : UpdateProgress()

    data class Downloading(
        val fraction: Float,
        val message: String,
    ) : UpdateProgress()

    object Restarting : UpdateProgress()

    data class Error(
        val message: String,
    ) : UpdateProgress()
}

object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private val os: String = System.getProperty("os.name").lowercase()
    private val platform: String =
        when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            else -> "linux"
        }

    // Set when the app was launched via `java -jar <path>` (as opposed to the native
    // launcher from a zip/dmg/msi/deb install) — `sun.java.command` is the jar path in that case.
    // .absoluteFile matters: launched as a bare "java -jar eventt.jar" (no directory component),
    // File("eventt.jar").parentFile is null, not the actual cwd — breaking every write/parent
    // check downstream (self-update showed "Can't write to null" instead of ever trying).
    private val jarPath: File? =
        System
            .getProperty("sun.java.command")
            ?.substringBefore(' ')
            ?.let { File(it).absoluteFile }
            ?.takeIf { it.name.endsWith(".jar") && it.isFile }

    fun checkLatestRelease(): UpdateInfo? {
        if (AppVersion.GITHUB_REPO.isBlank()) return null
        return try {
            val request =
                Request
                    .Builder()
                    .url("https://api.github.com/repos/${AppVersion.GITHUB_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val root = json.parseToJsonElement(body).jsonObject

            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: return null
            val latestVersion = tagName.removePrefix("v")

            if (!isNewer(latestVersion, AppVersion.NAME)) return null

            // Jar launches self-update by replacing the jar; everything else (zip/dmg/msi/deb
            // installs, all of which unpack the same portable app-image layout) uses the zip.
            val assetSuffix = if (jarPath != null) ".jar" else ".zip"
            val assets = root["assets"]?.jsonArray ?: return null
            val asset =
                assets.firstOrNull { elem ->
                    val name = elem.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    name.contains(platform, ignoreCase = true) && name.endsWith(assetSuffix)
                }
            val downloadUrl =
                asset
                    ?.jsonObject
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.content ?: return null

            UpdateInfo(
                version = latestVersion,
                tagName = tagName,
                downloadUrl = downloadUrl,
                releaseUrl = root["html_url"]?.jsonPrimitive?.content ?: "",
                releaseNotes = root["body"]?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            println("[UpdateChecker] Check failed: ${e.message}")
            null
        }
    }

    fun downloadAndInstall(
        info: UpdateInfo,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val updateDir = File(System.getProperty("user.home"), ".eventt/update").also { it.mkdirs() }
        try {
            val jar = jarPath
            if (jar != null) {
                downloadAndInstallJar(info, jar, updateDir, onProgress)
                return
            }

            // Fail fast, before spending bandwidth, if we can't write to our own install dir —
            // e.g. a system-wide Linux .deb install (root-owned) or a non-per-user Windows install.
            val installDir = currentInstallDir()
            if (installDir == null || !installDir.canWrite()) {
                onProgress(
                    UpdateProgress.Error(
                        "Can't write to the install folder" +
                            (installDir?.let { " (${it.absolutePath})" } ?: "") +
                            ". If this was installed system-wide (e.g. a .deb package), update it manually: ${info.releaseUrl}",
                    ),
                )
                return
            }

            val zipFile = File(updateDir, "update.zip")

            // 1. Download
            onProgress(UpdateProgress.Downloading(0f, "Downloading ${info.version}…"))
            val response = client.newCall(Request.Builder().url(info.downloadUrl).build()).execute()
            if (!response.isSuccessful) {
                onProgress(UpdateProgress.Error("Download failed: HTTP ${response.code}"))
                return
            }
            val total = response.body?.contentLength() ?: -1L
            var received = 0L
            response.body?.byteStream()?.use { src ->
                zipFile.outputStream().use { dst ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                        received += n
                        if (total > 0) {
                            onProgress(
                                UpdateProgress.Downloading(received.toFloat() / total * 0.8f, "Downloading…"),
                            )
                        }
                    }
                }
            }

            // 2. Extract
            onProgress(UpdateProgress.Downloading(0.85f, "Extracting…"))
            val extractDir =
                File(updateDir, "extracted").also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(extractDir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            // 3. Write restart script and exec
            onProgress(UpdateProgress.Downloading(0.95f, "Preparing restart…"))
            val newAppDir = extractDir.listFiles()?.firstOrNull { it.isDirectory }

            onProgress(UpdateProgress.Restarting)

            if (platform == "windows") {
                restartWindows(newAppDir, installDir, updateDir)
            } else {
                restartUnix(newAppDir, installDir, updateDir)
            }
        } catch (e: Exception) {
            onProgress(UpdateProgress.Error("Update failed: ${e.message}"))
        }
    }

    // Self-update for `java -jar` launches: replace the running jar in place and relaunch it.
    private fun downloadAndInstallJar(
        info: UpdateInfo,
        currentJar: File,
        updateDir: File,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        if (currentJar.parentFile?.canWrite() != true) {
            onProgress(
                UpdateProgress.Error(
                    "Can't write to ${currentJar.parentFile}. Download the update manually: ${info.releaseUrl}",
                ),
            )
            return
        }

        onProgress(UpdateProgress.Downloading(0f, "Downloading ${info.version}…"))
        val response = client.newCall(Request.Builder().url(info.downloadUrl).build()).execute()
        if (!response.isSuccessful) {
            onProgress(UpdateProgress.Error("Download failed: HTTP ${response.code}"))
            return
        }
        val newJar = File(updateDir, "update.jar")
        val total = response.body?.contentLength() ?: -1L
        var received = 0L
        response.body?.byteStream()?.use { src ->
            newJar.outputStream().use { dst ->
                val buf = ByteArray(65536)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    received += n
                    if (total > 0) {
                        onProgress(
                            UpdateProgress.Downloading(received.toFloat() / total * 0.9f, "Downloading…"),
                        )
                    }
                }
            }
        }

        onProgress(UpdateProgress.Downloading(0.95f, "Preparing restart…"))
        onProgress(UpdateProgress.Restarting)

        if (platform == "windows") {
            val script =
                buildString {
                    appendLine("@echo off")
                    appendLine("timeout /t 2 /nobreak > nul")
                    appendLine("copy /y \"${newJar.absolutePath}\" \"${currentJar.absolutePath}\"")
                    appendLine("start \"\" javaw -jar \"${currentJar.absolutePath}\"")
                }
            val scriptFile = File(updateDir, "restart-jar.bat").also { it.writeText(script) }
            Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", scriptFile.absolutePath))
        } else {
            val script =
                buildString {
                    appendLine("#!/bin/bash")
                    appendLine("sleep 2")
                    appendLine("cp '${newJar.absolutePath}' '${currentJar.absolutePath}'")
                    appendLine("exec java -jar '${currentJar.absolutePath}'")
                }
            val scriptFile =
                File(updateDir, "restart-jar.sh").also {
                    it.writeText(script)
                    it.setExecutable(true)
                }
            Runtime.getRuntime().exec(arrayOf("bash", scriptFile.absolutePath))
        }
        Thread.sleep(300)
        System.exit(0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun currentInstallDir(): File? {
        return try {
            val cmd =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null) ?: return null
            val exe = File(cmd)
            when (platform) {
                // .app/Contents/MacOS/exe  →  .app/
                "macos" -> exe.parentFile?.parentFile?.parentFile
                // <packageName>/<packageName>.exe  →  <packageName>/ — unlike Linux/macOS,
                // jpackage's Windows app-image has no bin/ subfolder.
                "windows" -> exe.parentFile
                // install/bin/exe  →  install/
                else -> exe.parentFile?.parentFile
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun restartUnix(
        newAppDir: File?,
        installDir: File?,
        updateDir: File,
    ) {
        val script =
            buildString {
                appendLine("#!/bin/bash")
                appendLine("sleep 2")
                if (newAppDir != null && installDir != null) {
                    appendLine("cp -r '${newAppDir.absolutePath}/.' '${installDir.absolutePath}/'")
                    val exe =
                        if (platform == "macos") {
                            appendLine("chmod +x '${installDir.absolutePath}/Contents/MacOS/'*")
                            "${installDir.absolutePath}/Contents/MacOS/eventt"
                        } else {
                            appendLine("chmod +x '${installDir.absolutePath}/bin/'*")
                            "${installDir.absolutePath}/bin/eventt"
                        }
                    appendLine("exec '$exe'")
                } else {
                    appendLine("echo 'Update downloaded to: ${updateDir.absolutePath}/extracted'")
                    appendLine("echo 'Please copy files manually and restart.'")
                }
            }
        val scriptFile =
            File(updateDir, "restart.sh").also {
                it.writeText(script)
                it.setExecutable(true)
            }
        Runtime.getRuntime().exec(arrayOf("bash", scriptFile.absolutePath))
        Thread.sleep(300)
        System.exit(0)
    }

    private fun restartWindows(
        newAppDir: File?,
        installDir: File?,
        updateDir: File,
    ) {
        val script =
            buildString {
                appendLine("@echo off")
                appendLine("timeout /t 3 /nobreak > nul")
                if (newAppDir != null && installDir != null) {
                    appendLine("xcopy /e /y /i \"${newAppDir.absolutePath}\\*\" \"${installDir.absolutePath}\\\"")
                    // No bin/ subfolder on Windows — the exe sits directly under installDir.
                    appendLine("start \"\" \"${installDir.absolutePath}\\eventt.exe\"")
                } else {
                    appendLine("echo Update downloaded to: ${updateDir.absolutePath}\\extracted")
                    appendLine("pause")
                }
            }
        val scriptFile = File(updateDir, "restart.bat").also { it.writeText(script) }
        Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", scriptFile.absolutePath))
        Thread.sleep(300)
        System.exit(0)
    }

    // Semantic version comparison: returns true if a > b
    private fun isNewer(
        a: String,
        b: String,
    ): Boolean {
        fun parts(v: String) = v.split(".").mapNotNull { it.toIntOrNull() }
        val av = parts(a)
        val bv = parts(b)
        for (i in 0 until maxOf(av.size, bv.size)) {
            val ai = av.getOrElse(i) { 0 }
            val bi = bv.getOrElse(i) { 0 }
            if (ai > bi) return true
            if (ai < bi) return false
        }
        return false
    }
}
