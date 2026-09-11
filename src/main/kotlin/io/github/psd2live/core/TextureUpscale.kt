package io.github.psd2live.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Local, optional nunif runtime. Paths are explicit: never silently select/download a model. */
@Serializable
data class TextureUpscaleConfig(
    val scale: Int = 1,
    val python: String = "python",
    val nunifDirectory: String = "",
    val modelDirectory: String = "",
    val tileSize: Int = 256,
    val neuralAlpha: Boolean = false,
) {
    init {
        require(scale in listOf(1, 2, 4)) { "Texture scale must be 1, 2 or 4" }
        require(tileSize in 64..512) { "Upscale tile size must be 64..512" }
    }

    companion object {
        /** Explicit UI shortcut for the conventional optional runtime installation. */
        fun localRuntime(): TextureUpscaleConfig? {
            val root = Path.of(System.getProperty("user.home"), ".psd2live", "runtime")
            val python = root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "python/Scripts/python.exe" else "python/bin/python")
            val repo = root.resolve("nunif")
            val models = listOf(
                repo.resolve("waifu2x/pretrained_models/swin_unet_v3/art"),
                repo.resolve("waifu2x/pretrained_models/swin_unet/art"),
            ).firstOrNull { Files.isDirectory(it) } ?: repo.resolve("waifu2x/pretrained_models/swin_unet_v3/art")
            return if (Files.isRegularFile(python) && Files.isDirectory(models))
                TextureUpscaleConfig(python = python.toString(), nunifDirectory = repo.toString(), modelDirectory = models.toString())
            else null
        }
    }
}

/** One model load for all cache misses; only one output layer is decoded at a time by the packer. */
internal object TextureUpscale {
    private val inferenceLock = java.util.concurrent.locks.ReentrantLock()
    private val workerBytes get() = requireNotNull(javaClass.getResourceAsStream("/upscale/nunif_worker.py")).use { it.readBytes() }

    fun prepare(
        layers: List<ClassifiedLayer>,
        config: TextureUpscaleConfig,
        progress: ProgressListener = ProgressListener { _, _ -> },
    ): Map<String, Path> {
        if (config.scale == 1 || layers.isEmpty()) return emptyMap()
        inferenceLock.lockInterruptibly()
        try { return prepareLocked(layers, config, progress) } finally { inferenceLock.unlock() }
    }

    private fun prepareLocked(
        layers: List<ClassifiedLayer>,
        config: TextureUpscaleConfig,
        progress: ProgressListener,
    ): Map<String, Path> {
        val repo = Path.of(config.nunifDirectory).toAbsolutePath().normalize()
        val models = Path.of(config.modelDirectory).toAbsolutePath().normalize()
        require(config.nunifDirectory.isNotBlank() && Files.isDirectory(repo.resolve("waifu2x"))) {
            "Configure the nunif source directory in Texture Upscale settings."
        }
        require(config.modelDirectory.isNotBlank() && Files.isDirectory(models)) {
            "Configure an explicit nunif Art model directory containing scale2x.pth or scale4x.pth."
        }
        // Fingerprint actual weights and inference code, not a user-supplied model label.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(workerBytes)
        digest.update(Json.encodeToString(config).toByteArray())
        val weights = Files.list(models).use { paths -> paths.filter { it.toString().endsWith(".pth") }.sorted().toList() }
        require(weights.isNotEmpty()) { "No .pth weights found in $models" }
        val code = listOf(repo.resolve("waifu2x"), repo.resolve("nunif")).flatMap { root ->
            if (!Files.isDirectory(root)) emptyList() else Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".py") }.sorted().toList()
            }
        }
        for (file in weights + code) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            digest.update(file.toString().toByteArray())
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
        }
        val identity = digest.digest()
        val cache = Path.of(System.getProperty("user.home"), ".psd2live", "cache", "upscale")
        Files.createDirectories(cache)
        val temporary = Files.createTempDirectory("psd2live-upscale-")
        try {
            val outputs = linkedMapOf<String, Path>()
            val tasks = mutableListOf<JsonObject>()
            val pending = mutableSetOf<String>()
            for (layer in layers) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val raster = layer.source.raster
                val hash = MessageDigest.getInstance("SHA-256").apply {
                    update(identity)
                    update("${raster.width}x${raster.height}".toByteArray())
                    update(raster.rgba)
                }.digest().joinToString("") { "%02x".format(it) }
                val output = cache.resolve("$hash.png")
                outputs[layer.source.id.raw] = output
                if (!validImage(output, raster.width * config.scale, raster.height * config.scale) && pending.add(hash)) {
                    val input = temporary.resolve("$hash.png")
                    ImageIO.write(PreviewRenderer.rasterImage(raster.width, raster.height, raster.rgba), "png", input.toFile())
                    tasks += buildJsonObject { put("input", input.toString()); put("output", output.toString()) }
                }
            }
            val cachedCount = layers.size - tasks.size
            if (cachedCount > 0) {
                progress.update(io.github.psd2live.i18n.tr("log.upscaleCacheHit", cachedCount, layers.size), if (tasks.isEmpty()) 0.95 else 0.05)
            }
            if (tasks.isNotEmpty()) {
                progress.update(io.github.psd2live.i18n.tr("upscale.startingInference"), 0.08)
                val script = temporary.resolve("worker.py")
                Files.write(script, workerBytes)
                val manifest = temporary.resolve("tasks.json")
                Files.writeString(manifest, JsonArray(tasks).toString())
                val log = temporary.resolve("worker.log")
                val command = mutableListOf(config.python, "-u", script.toString(), "--repo", repo.toString(),
                    "--models", models.toString(), "--scale", config.scale.toString(), "--tile", config.tileSize.toString(),
                    "--manifest", manifest.toString())
                if (config.neuralAlpha) command += "--neural-alpha"
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val process = ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start()
                val shutdown = Thread({
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }, "psd2live-upscale-shutdown")
                Runtime.getRuntime().addShutdownHook(shutdown)
                val logLines = mutableListOf<String>()
                val layerRegex = Regex("""Upscaling layer (\d+)/(\d+)""")
                try {
                    val reader = process.inputStream.bufferedReader(java.nio.charset.StandardCharsets.UTF_8)
                    var line = reader.readLine()
                    while (line != null) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        logLines.add(line)
                        val match = layerRegex.find(line)
                        if (match != null) {
                            val curr = match.groupValues[1].toInt()
                            val total = match.groupValues[2].toInt()
                            val frac = (0.08 + (curr.toDouble() / total) * 0.87).coerceIn(0.08, 0.95)
                            progress.update(io.github.psd2live.i18n.tr("upscale.processingLayer", curr, total), frac)
                        }
                        line = reader.readLine()
                    }
                    check(process.waitFor(30, TimeUnit.MINUTES)) { "Texture upscaling timed out after 30 minutes." }
                    check(process.exitValue() == 0) { "nunif upscaling failed:\n${logLines.takeLast(50).joinToString("\n")}" }
                    println(logLines.takeLast(20).joinToString("\n"))
                    progress.update(io.github.psd2live.i18n.tr("upscale.apply"), 1.0)
                } finally {
                    runCatching { Files.write(log, logLines) }
                    if (process.isAlive) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); runCatching { process.waitFor(5, TimeUnit.SECONDS) } }
                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
                }
            }
            for (layer in layers) {
                val raster = layer.source.raster
                check(validImage(outputs.getValue(layer.source.id.raw), raster.width * config.scale, raster.height * config.scale)) {
                    "Invalid upscaled RGBA output for ${layer.source.name}"
                }
            }
            return outputs
        } finally {
            // Only direct, owned temporary files; cache and original assets are never deleted here.
            Files.list(temporary).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(temporary)
        }
    }

    internal fun validImage(path: Path, width: Int, height: Int): Boolean = runCatching {
        if (!Files.isRegularFile(path)) return false
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return false
            val reader = readers.next()
            try {
                reader.input = input
                reader.getWidth(0) == width && reader.getHeight(0) == height &&
                    reader.getImageTypes(0).asSequence().any { it.colorModel.hasAlpha() }
            } finally { reader.dispose() }
        }
    }.getOrDefault(false)
}
