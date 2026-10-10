package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.config
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** An export owns one committed model and writes it as built; later GUI drafts or project switches cannot change it. */
internal class WorkspaceExportSession(
    private val captured: WorkspaceCapture<RigPreviewModel>,
    private val sourceName: String,
    private val pipeline: PSD2LivePipeline = PSD2LivePipeline(),
    private val publish: (List<Pair<Path, Path>>) -> Unit = { WorkspaceExportFiles.publish(it) },
) {
    suspend fun model(outputDirectory: Path): JsonObject {
        require(outputDirectory.isAbsolute && !Files.isRegularFile(outputDirectory)) { "Provide an absolute output directory" }
        val config = captured.model.config
        require(config.exportCmo3 || config.exportMoc3) { "Enable at least one model export format in settings" }
        val context = currentCoroutineContext()
        context.ensureActive()
        val target = outputDirectory.normalize()
        val stage = staging(target.parent ?: target)
        try {
            val result = runInterruptible(Dispatchers.Default) {
                pipeline.export(captured.model, sourceName, stage, progress(context, 0f, 0.9f))
            }
            val files = result.exportedFiles.map { file ->
                val relative = stage.relativize(file.path.toAbsolutePath().normalize())
                require(!relative.isAbsolute && relative.none { it.toString() == ".." }) { "Export escaped its staging directory" }
                file.path to target.resolve(relative)
            }
            context.ensureActive()
            val output = buildJsonObject {
                put("state", captured.state); put("revision", captured.revision)
                putJsonArray("files") { result.exportedFiles.zip(files).forEach { (file, paths) -> add(buildJsonObject {
                    put("path", paths.second.toString()); put("bytes", file.bytes)
                }) } }
                put("warnings", JsonArray(result.warnings.map(::JsonPrimitive)))
            }
            return complete(context, files, output)
        } finally { cleanup(stage) }
    }

    /** Exports the captured rig through [targetId]; the files and the loss report land in [outputDirectory]. */
    suspend fun target(targetId: String, outputDirectory: Path, settings: Map<String, String>): JsonObject {
        require(outputDirectory.isAbsolute && !Files.isRegularFile(outputDirectory)) { "Provide an absolute output directory" }
        val config = captured.model.config
        val target = ExportService.registry(config)[targetId]
        val baseName = sourceName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifEmpty { "model" }
        val context = currentCoroutineContext()
        context.ensureActive()
        val report = runInterruptible(Dispatchers.Default) {
            ExportService.export(captured.model, target, ExportService.options(target, baseName, config, settings), outputDirectory.normalize())
        }
        return buildJsonObject {
            put("state", captured.state); put("revision", captured.revision); put("target", report.target); put("compiler", report.compiler)
            put("directory", outputDirectory.normalize().toString())
            putJsonArray("files") { report.files.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("losses") { report.losses.forEach { loss -> add(buildJsonObject {
                put("object", loss.objectId); put("feature", loss.feature.name.lowercase()); put("handling", loss.handling.name.lowercase())
                loss.error?.let { put("error", it) }; put("note", loss.note)
            }) } }
        }
    }

    suspend fun psd(path: Path, scale: Int, includeGeneratedLayers: Boolean): JsonObject {
        require(scale in setOf(1, 2, 4)) { "PSD scale must be 1, 2, or 4" }
        require(path.isAbsolute && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute PSD output path" }
        val context = currentCoroutineContext()
        context.ensureActive()
        val analysis = captured.model.analysis
        val layers = if (includeGeneratedLayers) analysis.layers.map { it.source } else captured.document.source.layers
        val upscaleProgress = progress(context, 0.05f, 0.8f)
        val bytes = runInterruptible(Dispatchers.Default) {
            val upscaled = if (scale > 1) TextureUpscale.prepare(analysis.layers,
                captured.document.config().textureUpscale.copy(scale = scale)) { message, fraction ->
                upscaleProgress.update(message, fraction)
            } else emptyMap()
            context.ensureActive()
            progress(context, 0f, 1f).update("Writing PSD", 0.85)
            org.umamo.format.psd.PsdWriter.write(captured.document.source.widthPx, captured.document.source.heightPx,
                layers, captured.document.source.groups, scale, upscaled)
        }
        val target = path.normalize()
        val stage = staging(target.parent)
        try {
            val file = stage.resolve(target.fileName)
            runInterruptible(Dispatchers.IO) { Files.write(file, bytes) }
            context.ensureActive()
            val output = buildJsonObject {
                put("state", captured.state); put("path", target.toString()); put("bytes", bytes.size); put("layers", layers.size)
            }
            return complete(context, listOf(file to target), output)
        } finally { cleanup(stage) }
    }

    private suspend fun complete(context: kotlin.coroutines.CoroutineContext, files: List<Pair<Path, Path>>, output: JsonObject): JsonObject {
        context.ensureActive()
        // Publishing is a bounded file transaction. Cancellation before this boundary writes no files;
        // afterwards the exact successful result is retained before a dispatcher can deliver cancellation.
        return withContext(NonCancellable + Dispatchers.IO) {
            publish(files)
            context[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(output))
            output
        }
    }

    private fun progress(context: kotlin.coroutines.CoroutineContext, start: Float, end: Float): ProgressListener {
        val lock = Any()
        var highest = start
        return ProgressListener { message, fraction ->
            context.ensureActive()
            synchronized(lock) {
                highest = maxOf(highest, start + (end - start) * fraction.toFloat().coerceIn(0f, 1f))
                context[WorkspaceJobContext]?.progress(highest, message)
            }
        }
    }

    private suspend fun staging(parent: Path): Path = runInterruptible(Dispatchers.IO) {
        Files.createDirectories(parent)
        Files.createTempDirectory(parent, ".psd2live-export-").toAbsolutePath().normalize()
    }

    private suspend fun cleanup(stage: Path) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { Files.walk(stage).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        Unit
    }
}

/** Restore overwritten files if publishing a file family fails partway through. */
internal object WorkspaceExportFiles {
    fun publish(files: List<Pair<Path, Path>>, move: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); Unit
    }) {
        require(files.map { it.second }.distinct().size == files.size) { "Duplicate export destination" }
        files.forEach { (source, target) ->
            require(Files.isRegularFile(source)) { "Missing staged export file" }
            require(!Files.exists(target) || Files.isRegularFile(target)) { "An export destination is not a file: $target" }
        }
        val backups = linkedMapOf<Path, Path?>()
        val written = mutableListOf<Path>()
        val keepBackups = HashSet<Path>()
        try {
            files.forEach { (_, target) ->
                Files.createDirectories(target.parent)
                val backup = if (Files.exists(target)) Files.createTempFile(target.parent, ".psd2live-backup-", ".tmp") else null
                backups[target] = backup
                if (backup != null) Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
            }
            files.forEach { (source, target) ->
                written.add(target)
                move(source, target)
            }
        } catch (failure: Exception) {
            written.asReversed().forEach { target ->
                val backup = backups[target]
                runCatching {
                    if (backup == null) Files.deleteIfExists(target)
                    else Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING)
                }.exceptionOrNull()?.let { restoreFailure ->
                    if (backup != null) keepBackups.add(backup)
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        } finally {
            backups.values.filterNotNull().filterNot { it in keepBackups }.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
