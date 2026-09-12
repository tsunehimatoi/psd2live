package io.github.psd2live.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed interface DownloadState {
    object Idle : DownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val currentItem: String,
        val progress: Float,
    ) : DownloadState
    object Verifying : DownloadState
    object Extracting : DownloadState
    data class Success(val modelDir: Path, val nunifDir: Path) : DownloadState
    data class Failed(val error: String) : DownloadState
}

object ModelDownloader {
    const val MODEL_ZIP_NAME = "waifu2x_pretrained_models_swin_unet_v3_art_20260816.zip"
    const val MODEL_SHA256 = "d623590525e4438b92d8cc0404ff58c427ab79b79c4b02de12ce132e6d89e82e"
    const val MODEL_TOTAL_SIZE = 115825363L // ~110.5 MB

    private val MODEL_URLS = listOf(
        "https://github.com/nagadomi/nunif/releases/download/0.0.0/waifu2x_pretrained_models_swin_unet_v3_art_20260816.zip",
        "https://ghfast.top/https://github.com/nagadomi/nunif/releases/download/0.0.0/waifu2x_pretrained_models_swin_unet_v3_art_20260816.zip",
        "https://mirror.ghproxy.com/https://github.com/nagadomi/nunif/releases/download/0.0.0/waifu2x_pretrained_models_swin_unet_v3_art_20260816.zip",
    )

    private val NUNIF_URLS = listOf(
        "https://codeload.github.com/nagadomi/nunif/zip/refs/heads/dev",
        "https://ghfast.top/https://github.com/nagadomi/nunif/archive/refs/heads/dev.zip",
        "https://mirror.ghproxy.com/https://github.com/nagadomi/nunif/archive/refs/heads/dev.zip",
    )

    fun defaultRuntimeDir(): Path =
        Path.of(System.getProperty("user.home"), ".psd2live", "runtime")

    fun defaultNunifDir(): Path =
        defaultRuntimeDir().resolve("nunif")

    fun defaultModelDir(): Path =
        defaultNunifDir().resolve("waifu2x/pretrained_models/swin_unet_v3/art")

    fun isModelInstalled(dir: Path = defaultModelDir()): Boolean {
        if (!Files.isDirectory(dir)) return false
        val scale2x = dir.resolve("scale2x.pth")
        val noise1 = dir.resolve("noise1_scale2x.pth")
        return Files.isRegularFile(scale2x) && Files.isRegularFile(noise1) &&
            runCatching { Files.size(scale2x) > 1_000_000 }.getOrDefault(false)
    }

    fun isNunifInstalled(dir: Path = defaultNunifDir()): Boolean {
        return Files.isDirectory(dir.resolve("waifu2x")) && Files.isDirectory(dir.resolve("nunif"))
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    suspend fun downloadAndInstall(
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onStateChange: (DownloadState) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val runtimeDir = defaultRuntimeDir()
        val nunifDir = defaultNunifDir()
        val modelDir = defaultModelDir()
        Files.createDirectories(runtimeDir)

        val tempDir = Files.createTempDirectory("psd2live-download-")
        try {
            // Step 1: Check and download nunif repo if not present (~2.5 MB)
            if (!isNunifInstalled(nunifDir)) {
                onStateChange(DownloadState.Downloading(0, 2_600_000L, 0, "nunif", 0.01f))
                val nunifZip = tempDir.resolve("nunif-dev.zip")
                val nunifSuccess = downloadWithFallback(
                    urls = NUNIF_URLS,
                    targetFile = nunifZip,
                    expectedSha256 = null,
                    itemName = "nunif",
                    cancelFlag = cancelFlag,
                    onProgress = { read, total, speed ->
                        val p = if (total > 0) (read.toFloat() / total.toFloat()) * 0.08f else 0.04f
                        onStateChange(DownloadState.Downloading(read, total, speed, "nunif", p.coerceIn(0.01f, 0.08f)))
                    }
                )
                if (!nunifSuccess) {
                    onStateChange(DownloadState.Failed("Failed to download nunif framework from all mirror sources."))
                    return@withContext false
                }
                if (cancelFlag.get()) {
                    onStateChange(DownloadState.Idle)
                    return@withContext false
                }
                onStateChange(DownloadState.Extracting)
                extractNunifZip(nunifZip, nunifDir)
            }

            // Step 2: Download Model weights (~110.5 MB)
            val modelZip = tempDir.resolve(MODEL_ZIP_NAME)
            onStateChange(DownloadState.Downloading(0, MODEL_TOTAL_SIZE, 0, "model", 0.10f))

            val modelSuccess = downloadWithFallback(
                urls = MODEL_URLS,
                targetFile = modelZip,
                expectedSha256 = MODEL_SHA256,
                itemName = "model",
                cancelFlag = cancelFlag,
                onProgress = { read, total, speed ->
                    val frac = if (total > 0) (read.toFloat() / total.toFloat()) * 0.82f else 0.4f
                    val overallProgress = (0.10f + frac).coerceIn(0.10f, 0.92f)
                    onStateChange(DownloadState.Downloading(read, total, speed, "model", overallProgress))
                }
            )
            if (!modelSuccess) {
                if (cancelFlag.get()) {
                    onStateChange(DownloadState.Idle)
                } else {
                    onStateChange(DownloadState.Failed("Failed to download SwinUNet v3 model weights."))
                }
                return@withContext false
            }

            if (cancelFlag.get()) {
                onStateChange(DownloadState.Idle)
                return@withContext false
            }

            // Step 3: Extract model weights
            onStateChange(DownloadState.Extracting)
            Files.createDirectories(modelDir)
            extractModelZip(modelZip, modelDir)

            // Step 4: Verify installed weights
            if (!isModelInstalled(modelDir)) {
                onStateChange(DownloadState.Failed("Verification failed: expected weights not found after extraction."))
                return@withContext false
            }

            onStateChange(DownloadState.Success(modelDir, nunifDir))
            true
        } catch (e: Exception) {
            if (cancelFlag.get()) {
                onStateChange(DownloadState.Idle)
            } else {
                onStateChange(DownloadState.Failed(e.localizedMessage ?: e.message ?: "Unknown download error"))
            }
            false
        } finally {
            runCatching {
                Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(tempDir)
            }
        }
    }

    private fun downloadWithFallback(
        urls: List<String>,
        targetFile: Path,
        expectedSha256: String?,
        itemName: String,
        cancelFlag: AtomicBoolean,
        onProgress: (read: Long, total: Long, speed: Long) -> Unit,
    ): Boolean {
        for (url in urls) {
            if (cancelFlag.get()) return false
            try {
                val success = downloadSingleUrl(url, targetFile, expectedSha256, cancelFlag, onProgress)
                if (success) return true
            } catch (_: Exception) {
                // Try next mirror
            }
        }
        return false
    }

    private fun downloadSingleUrl(
        urlString: String,
        targetFile: Path,
        expectedSha256: String?,
        cancelFlag: AtomicBoolean,
        onProgress: (read: Long, total: Long, speed: Long) -> Unit,
    ): Boolean {
        Files.deleteIfExists(targetFile)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(urlString))
            .header("User-Agent", "PSD2Live-ModelDownloader/0.6.0")
            .timeout(Duration.ofMinutes(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            return false
        }

        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null

        val buffer = ByteArray(65536)
        var totalRead = 0L
        var lastReportTime = System.currentTimeMillis()
        var bytesSinceLastReport = 0L
        var currentSpeed = 0L

        response.body().use { input ->
            Files.newOutputStream(targetFile).use { output ->
                while (!cancelFlag.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest?.update(buffer, 0, n)
                    totalRead += n
                    bytesSinceLastReport += n

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastReportTime
                    if (elapsed >= 300) {
                        currentSpeed = (bytesSinceLastReport * 1000L) / elapsed.coerceAtLeast(1L)
                        onProgress(totalRead, contentLength, currentSpeed)
                        lastReportTime = now
                        bytesSinceLastReport = 0L
                    }
                }
            }
        }

        if (cancelFlag.get()) {
            Files.deleteIfExists(targetFile)
            return false
        }

        onProgress(totalRead, contentLength, currentSpeed)

        if (expectedSha256 != null && digest != null) {
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hash.equals(expectedSha256, ignoreCase = true)) {
                Files.deleteIfExists(targetFile)
                throw IllegalStateException("SHA-256 mismatch: expected $expectedSha256, got $hash")
            }
        }
        return true
    }

    internal fun extractNunifZip(zipPath: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        ZipInputStream(BufferedInputStream(Files.newInputStream(zipPath))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                // Strip root folder e.g. "nunif-dev/"
                val subPath = if (name.contains('/')) name.substringAfter('/') else ""
                if (subPath.isNotBlank()) {
                    val dest = targetDir.resolve(subPath)
                    if (entry.isDirectory) {
                        Files.createDirectories(dest)
                    } else {
                        Files.createDirectories(dest.parent)
                        Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    internal fun extractModelZip(zipPath: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        ZipInputStream(BufferedInputStream(Files.newInputStream(zipPath))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && name.endsWith(".pth", ignoreCase = true)) {
                    val fileName = name.substringAfterLast('/')
                    val dest = targetDir.resolve(fileName)
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

