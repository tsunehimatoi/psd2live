package io.github.psd2live.core.modifier

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Controlled builder for `MANAGED_BUILD` (Gradle `custom-plugin`) and `BUILT_PACKAGE` dev sources. */
internal object PluginDevBuilder {
    const val DEFAULT_CANDIDATE_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
    const val DEFAULT_BUILD_TIMEOUT_MILLIS: Long = 180_000L
    private const val MAX_PACKAGE_BYTES = 512L * 1024L * 1024L
    private val digestRegex = Regex("[a-f0-9]{64}")
    private val tokenRegex = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun defaultLogDir(): Path =
        Path.of(System.getProperty("user.home"), ".psd2live", "dev-logs")

    fun build(
        sourceId: String,
        sourceStore: PluginDevSourceStore = PluginDevSourceStore.shared,
        sdkDir: Path = PluginDevContracts.defaultSdkDir(),
        logDir: Path = defaultLogDir(),
        ttlMillis: Long = DEFAULT_CANDIDATE_TTL_MILLIS,
        timeoutMillis: Long = DEFAULT_BUILD_TIMEOUT_MILLIS,
        dependencyLock: List<ModifierPluginReference> = emptyList(),
        sealArtifact: (
            stagedDir: Path,
            sourceId: String,
            dependencyLock: List<ModifierPluginReference>,
            ttlMillis: Long,
        ) -> PluginDevArtifact = { stagedDir, srcId, depLock, ttl ->
            PluginDevArtifactStore.seal(stagedDir, srcId, depLock, ttl)
        },
        listArtifacts: (sourceId: String) -> List<PluginDevArtifact> = { srcId ->
            PluginDevArtifactStore.list(srcId)
        },
    ): PluginDevBuildResult {
        val sourceCheck = sourceStore.get(sourceId)
            ?: throw PluginDevException("dev_source_missing", "Unknown dev source: $sourceId")

        return sourceStore.withSourceLock(sourceId) {
            val source = sourceStore.get(sourceId)
                ?: throw PluginDevException("dev_source_missing", "Unknown dev source: $sourceId")
            if (!Files.exists(source.source)) {
                throw PluginDevException("dev_source_missing", "Dev source path does not exist: ${source.source}")
            }
            checkCancelled()

            val existing = runCatching { listArtifacts(sourceId).toList() }.getOrDefault(emptyList())
            when (source.mode) {
                PluginDevSourceMode.BUILT_PACKAGE ->
                    buildFromPrebuiltPackage(source, sourceStore, ttlMillis, dependencyLock, existing, sealArtifact)
                PluginDevSourceMode.MANAGED_BUILD ->
                    buildManagedProject(source, sourceStore, sdkDir, logDir, ttlMillis, timeoutMillis, dependencyLock, existing, sealArtifact)
            }
        }
    }

    private fun buildFromPrebuiltPackage(
        source: PluginDevSource,
        sourceStore: PluginDevSourceStore,
        ttlMillis: Long,
        dependencyLock: List<ModifierPluginReference>,
        existing: List<PluginDevArtifact>,
        sealArtifact: (Path, String, List<ModifierPluginReference>, Long) -> PluginDevArtifact,
    ): PluginDevBuildResult {
        val stagingRoot = Files.createTempDirectory("psd2live-dev-stage-")
        val stagedDir = Files.createDirectory(stagingRoot.resolve("package"))
        try {
            if (Files.isDirectory(source.source)) {
                copyDirectory(source.source, stagedDir, skipBuildDirs = false)
            } else {
                unzip(source.source, stagedDir)
            }
            val manifest = validateStagedPackage(stagedDir)
            val buildId = computePackageBuildId(stagedDir, manifest)
            val baseVersion = manifest.version.substringBefore('+')
            val devVersion = "$baseVersion+dev.$buildId"
            if (manifest.version != devVersion) {
                val updated = manifest.copy(version = devVersion)
                Files.writeString(stagedDir.resolve("plugin.json"), codec.encodeToString(ModifierPluginManifest.serializer(), updated))
            }
            val artifact = sealArtifact(stagedDir, source.sourceId, dependencyLock, ttlMillis)
            val unchanged = existing.any { it.artifactId == artifact.artifactId || it.buildDigest == artifact.buildDigest }
            sourceStore.updatePackageIds(source.sourceId, listOf(artifact.manifest.id), status = "built")
            return PluginDevBuildResult(
                artifact = artifact,
                unchanged = unchanged,
                diagnostics = emptyList(),
                logRef = null,
            )
        } finally {
            deleteTreeQuietly(stagingRoot)
        }
    }

    private fun buildManagedProject(
        source: PluginDevSource,
        sourceStore: PluginDevSourceStore,
        sdkDir: Path,
        logDir: Path,
        ttlMillis: Long,
        timeoutMillis: Long,
        dependencyLock: List<ModifierPluginReference>,
        existing: List<PluginDevArtifact>,
        sealArtifact: (Path, String, List<ModifierPluginReference>, Long) -> PluginDevArtifact,
    ): PluginDevBuildResult {
        val profile = source.profileId ?: PluginDevContracts.CUSTOM_PLUGIN_PROFILE
        if (profile != PluginDevContracts.CUSTOM_PLUGIN_PROFILE) {
            throw PluginDevException("build_failed", "Unsupported build profile: $profile")
        }

        val env = PluginDevContracts.environmentCheck(
            sdkDir = sdkDir,
            projectDir = source.source,
            stageSdkIfMissing = true,
        )
        if (!env.ready) {
            throw PluginDevException(
                code = "build_failed",
                message = "Build environment is not ready: ${env.missing.joinToString()}",
                diagnostics = env.diagnostics,
            )
        }

        val preFingerprint = computeSourceFingerprint(source.source)
        val buildId = preFingerprint.take(12)
        val baseVersion = readBaseVersion(source.source)
        val devVersion = "$baseVersion+dev.$buildId"

        Files.createDirectories(logDir)
        val logFile = logDir.resolve("${source.sourceId}-${System.currentTimeMillis()}.log")

        val command = env.gradleCommand + listOf(
            "pluginPackage",
            "--no-daemon",
            "-Ppsd2live.sdkDir=${env.sdkDir.toAbsolutePath()}",
            "-Ppsd2live.pluginVersion=$devVersion",
        )
        val exitCode = runGradleProcess(command, source.source, logFile, timeoutMillis)

        val postFingerprint = computeSourceFingerprint(source.source)
        if (postFingerprint != preFingerprint) {
            throw PluginDevException(
                code = "artifact_changed",
                message = "Plugin source files changed during build",
                logRef = logFile.toString(),
            )
        }

        if (exitCode != 0) {
            val logText = runCatching { Files.readString(logFile) }.getOrDefault("")
            val diagnostics = parseCompilerDiagnostics(logText)
            throw PluginDevException(
                code = "build_failed",
                message = diagnostics.firstOrNull()?.message ?: "Plugin build failed (exit $exitCode)",
                diagnostics = diagnostics,
                logRef = logFile.toString(),
            )
        }

        val builtOutputDir = source.source.resolve("build").resolve("plugin")
        if (!Files.isRegularFile(builtOutputDir.resolve("plugin.json"))) {
            throw PluginDevException(
                code = "build_failed",
                message = "Build succeeded but did not produce build/plugin/plugin.json",
                logRef = logFile.toString(),
            )
        }

        val stagingRoot = Files.createTempDirectory("psd2live-dev-stage-")
        val stagedDir = Files.createDirectory(stagingRoot.resolve("package"))
        try {
            copyDirectory(builtOutputDir, stagedDir, skipBuildDirs = false)
            validateStagedPackage(stagedDir)
            val artifact = sealArtifact(stagedDir, source.sourceId, dependencyLock, ttlMillis)
            val unchanged = existing.any { it.artifactId == artifact.artifactId || it.buildDigest == artifact.buildDigest }
            sourceStore.updatePackageIds(source.sourceId, listOf(artifact.manifest.id), status = "built")
            return PluginDevBuildResult(
                artifact = artifact,
                unchanged = unchanged,
                diagnostics = emptyList(),
                logRef = logFile.toString(),
            )
        } finally {
            deleteTreeQuietly(stagingRoot)
        }
    }

    private fun runGradleProcess(
        command: List<String>,
        workingDir: Path,
        logFile: Path,
        timeoutMillis: Long,
    ): Int {
        val process = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        try {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(1_000L))
            while (true) {
                checkCancelled()
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMs <= 0L) {
                    destroyProcessTree(process)
                    throw PluginDevException(
                        code = "build_timeout",
                        message = "Plugin build timed out after ${timeoutMillis}ms",
                        logRef = logFile.toString(),
                    )
                }
                if (process.waitFor(minOf(remainingMs, 250L), TimeUnit.MILLISECONDS)) {
                    return process.exitValue()
                }
            }
        } catch (cancelled: CancellationException) {
            destroyProcessTree(process)
            throw cancelled
        } catch (interrupted: InterruptedException) {
            destroyProcessTree(process)
            Thread.currentThread().interrupt()
            throw CancellationException("Plugin build cancelled").apply { initCause(interrupted) }
        }
    }

    private fun destroyProcessTree(process: Process) {
        val interrupted = Thread.interrupted()
        try {
            if (process.isAlive) {
                val children = process.descendants().toList()
                children.forEach { runCatching { it.destroyForcibly() } }
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(5, TimeUnit.SECONDS) }
                children.forEach { runCatching { it.onExit().get(2, TimeUnit.SECONDS) } }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    internal fun parseCompilerDiagnostics(logText: String): List<PluginDevDiagnostic> {
        val results = ArrayList<PluginDevDiagnostic>()
        // Matches:
        // e: file:///path/to/File.kt:12:5 Unresolved reference: foo
        // e: C:\path\File.kt:12:5 Unresolved reference: foo
        // e: C:\path\File.kt: (12, 5): Unresolved reference: foo
        val kotlinFileLine = Regex("""^e:\s+(?:file:///?)?(.+?):(?:\s*\((\d+),\s*(\d+)\):|(\d+):(\d+))\s*(.+)$""")
        for (rawLine in logText.lineSequence()) {
            val line = rawLine.trim()
            val match = kotlinFileLine.matchEntire(line)
            if (match != null) {
                val file = match.groupValues[1].trim()
                val lineNum = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: 0
                val colNum = (match.groupValues[3].ifEmpty { match.groupValues[5] }).toIntOrNull() ?: 0
                val msg = match.groupValues[6].trim()
                results.add(
                    PluginDevDiagnostic(
                        code = "build_failed",
                        severity = "error",
                        message = "$file:$lineNum:$colNum: $msg",
                        details = buildJsonObject {
                            put("file", file)
                            put("line", lineNum)
                            put("column", colNum)
                            put("detail", msg)
                        },
                    )
                )
            }
        }
        return results
    }

    private fun readBaseVersion(sourceDir: Path): String {
        val metaFile = sourceDir.resolve("plugin-meta.json")
        if (!Files.isRegularFile(metaFile)) return "0.1.0"
        return runCatching {
            val obj = Json.parseToJsonElement(Files.readString(metaFile)).jsonObject
            val raw = obj["version"]?.jsonPrimitive?.content ?: "0.1.0"
            raw.substringBefore('+').also { ModifierPluginVersion.parse(it, legacy = true) }
        }.getOrDefault("0.1.0")
    }

    internal fun computeSourceFingerprint(sourceDir: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = ArrayList<Path>()
        Files.walk(sourceDir).use { stream ->
            stream.forEach { path ->
                val rel = sourceDir.relativize(path).toString().replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                if (rel == "build" || rel.startsWith("build/") ||
                    rel == ".gradle" || rel.startsWith(".gradle/") ||
                    rel == ".git" || rel.startsWith(".git/")
                ) return@forEach
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) files.add(path)
            }
        }
        files.sortBy { sourceDir.relativize(it).toString().replace('\\', '/') }
        for (file in files) {
            val rel = sourceDir.relativize(file).toString().replace('\\', '/')
            digest.update(rel.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(Files.readAllBytes(file))
            digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun computePackageBuildId(directory: Path, manifest: ModifierPluginManifest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(directory.resolve("plugin.json")))
        manifest.files.toSortedMap().forEach { (name, hash) ->
            digest.update('\n'.code.toByte())
            digest.update("$name:$hash".toByteArray(Charsets.UTF_8))
        }
        return HexFormat.of().formatHex(digest.digest()).take(12)
    }

    internal fun validateStagedPackage(directory: Path): ModifierPluginManifest {
        val manifestFile = directory.resolve("plugin.json")
        if (!Files.isRegularFile(manifestFile, NOFOLLOW_LINKS)) {
            throw PluginDevException("build_failed", "Missing plugin.json in built package")
        }
        val bytes = Files.readAllBytes(manifestFile)
        val manifest = runCatching { codec.decodeFromString(ModifierPluginManifest.serializer(), bytes.decodeToString()) }
            .getOrElse { throw PluginDevException("build_failed", "Invalid plugin.json: ${it.message}", cause = it) }

        try {
            require(manifest.id != "builtin" && !manifest.id.startsWith("builtin.")) {
                "Custom plugins cannot use reserved 'builtin' namespace"
            }
            require(manifest.protocol in PluginDevContracts.HOST_PROTOCOL_MIN..PluginDevContracts.HOST_PROTOCOL_MAX) {
                "Unsupported plugin protocol: ${manifest.protocol}"
            }
            require(tokenRegex.matches(manifest.id) && manifest.version.length <= 128) {
                "Invalid plugin ID or version"
            }
            ModifierPluginVersion.parse(manifest.version, legacy = true)
            require(manifest.name.isNotBlank() && manifest.algorithms.size in 1..128) {
                "Plugin manifest must declare 1..128 algorithms"
            }
            manifest.algorithms.forEach { alg ->
                require(alg.type.startsWith("${manifest.id}/") && alg.version > 0 && alg.name.isNotBlank()) {
                    "Algorithm type '${alg.type}' must belong to '${manifest.id}/'"
                }
                require(alg.targets.isNotEmpty() && alg.targets.all { it in PluginDevContracts.supportedTargets }) {
                    "Unsupported algorithm targets: ${alg.targets}"
                }
                require((alg.reads + alg.writes + alg.composableKinds).all { it in ModifierPluginOutput.components }) {
                    "Unknown plugin model component in reads/writes/composableKinds"
                }
                require("*" !in alg.creates && "*" !in alg.modifies) {
                    "Wildcard '*' is forbidden in creates/modifies declarations"
                }
                ModifierPluginSchema.check(alg.configSchema)
                ModifierPluginSchema.validate(alg.defaults, alg.configSchema)
            }
            val command = requireNotNull(manifest.commands[ModifierPluginStore.platform] ?: manifest.commands["jvm"]) {
                "Plugin has no runtime command for ${ModifierPluginStore.platform} or jvm"
            }
            require(command.isNotEmpty() && command.size <= 64) { "Invalid plugin command" }
            require(manifest.files.size in 1..10_000) { "Plugin payload files map is empty or too large" }
            var totalBytes = 0L
            manifest.files.toSortedMap().forEach { (name, expectedHash) ->
                val path = ModifierPluginStore.inside(directory, name)
                require(digestRegex.matches(expectedHash) && Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    "Missing plugin payload: $name"
                }
                totalBytes += Files.size(path)
                require(totalBytes <= MAX_PACKAGE_BYTES) { "Plugin payload exceeds size limit" }
                require(ModifierPluginStore.hash(path) == expectedHash) { "Plugin checksum mismatch: $name" }
            }
            if (command.first() == "\$JAVA") {
                require(command.size == 3 && command[1] == "-jar" && command[2] in manifest.files) {
                    "JVM plugins must specify \$JAVA -jar <packaged.jar>"
                }
            } else {
                require(command.first() in manifest.files) { "Plugin executable must be packaged" }
            }
        } catch (failure: IllegalArgumentException) {
            throw PluginDevException("plugin_api_incompatible", failure.message ?: "Incompatible plugin manifest", cause = failure)
        }
        return manifest
    }

    private fun copyDirectory(source: Path, destination: Path, skipBuildDirs: Boolean) {
        var totalBytes = 0L
        var count = 0
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                checkCancelled()
                val rel = source.relativize(path).toString().replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                if (skipBuildDirs && (rel == ".git" || rel.startsWith(".git/"))) return@forEach
                require(++count <= 10_000) { "Too many plugin files" }
                require(!Files.isSymbolicLink(path)) { "Plugin symlinks are not supported" }
                val target = ModifierPluginStore.inside(destination, rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    totalBytes += Files.size(path)
                    require(totalBytes <= MAX_PACKAGE_BYTES) { "Plugin payload exceeds size limit" }
                    Files.createDirectories(target.parent)
                    Files.copy(path, target)
                    if (Files.isExecutable(path)) target.toFile().setExecutable(true)
                }
            }
        }
    }

    private fun unzip(source: Path, destination: Path) {
        var totalBytes = 0L
        var count = 0
        ZipInputStream(Files.newInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 10_000) { "Too many plugin files" }
                val rel = entry.name.trimEnd('/')
                if (rel.isEmpty()) continue
                val target = ModifierPluginStore.inside(destination, rel)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    require(!Files.exists(target)) { "Duplicate plugin archive entry: $rel" }
                    Files.newOutputStream(target).use { out ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            checkCancelled()
                            val n = zip.read(buffer)
                            if (n < 0) break
                            totalBytes += n
                            require(totalBytes <= MAX_PACKAGE_BYTES) { "Plugin archive exceeds size limit" }
                            out.write(buffer, 0, n)
                        }
                    }
                    target.toFile().setExecutable(true, true)
                }
            }
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Plugin build cancelled")
    }

    private fun deleteTreeQuietly(path: Path) {
        runCatching { ModifierPluginStore.deleteTree(path) }
    }
}
