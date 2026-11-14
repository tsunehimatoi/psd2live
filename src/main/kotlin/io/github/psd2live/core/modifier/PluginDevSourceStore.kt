package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Persists registered custom plugin dev sources in `~/.psd2live/dev-sources.json` and serializes per-source builds. */
internal class PluginDevSourceStore(val file: Path) {
    companion object {
        val shared by lazy {
            PluginDevSourceStore(Path.of(System.getProperty("user.home"), ".psd2live", "dev-sources.json"))
        }
        private val codec = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }

    @Serializable
    private data class StoredSource(
        val sourceId: String,
        val source: String,
        val mode: String,
        val profileId: String? = null,
        val status: String = "registered",
        val packageIds: List<String> = emptyList(),
    ) {
        fun toApi(): PluginDevSource = PluginDevSource(
            sourceId = sourceId,
            source = Path.of(source),
            mode = PluginDevSourceMode.valueOf(mode),
            profileId = profileId,
            status = status,
            packageIds = packageIds,
        )
    }

    private val lock = Any()
    private val buildLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun list(): List<PluginDevSource> = synchronized(lock) {
        readAll().map { it.toApi() }
    }

    fun get(sourceId: String): PluginDevSource? = synchronized(lock) {
        readAll().firstOrNull { it.sourceId == sourceId }?.toApi()
    }

    fun register(
        source: Path,
        mode: PluginDevSourceMode,
        profileId: String? = null,
        sourceId: String? = null,
    ): PluginDevSource {
        val normalized = source.toAbsolutePath().normalize()
        if (!Files.exists(normalized)) {
            throw PluginDevException("dev_source_missing", "Dev source path does not exist: $normalized")
        }
        val effectiveProfile = when (mode) {
            PluginDevSourceMode.MANAGED_BUILD -> {
                require(Files.isDirectory(normalized)) { "Managed build source must be a directory: $normalized" }
                val profile = profileId ?: PluginDevContracts.CUSTOM_PLUGIN_PROFILE
                require(profile == PluginDevContracts.CUSTOM_PLUGIN_PROFILE) {
                    "Unsupported build profile '$profile'; expected '${PluginDevContracts.CUSTOM_PLUGIN_PROFILE}'"
                }
                profile
            }
            PluginDevSourceMode.BUILT_PACKAGE -> {
                require(Files.isDirectory(normalized) || normalized.fileName.toString().endsWith(".zip")) {
                    "Built package source must be a directory or .zip archive: $normalized"
                }
                null
            }
        }

        return synchronized(lock) {
            val entries = readAll().toMutableList()
            val existingByPath = entries.indexOfFirst {
                Path.of(it.source).toAbsolutePath().normalize() == normalized && it.mode == mode.name
            }
            val resolvedId = sourceId?.takeIf { it.isNotBlank() }
                ?: if (existingByPath >= 0) entries[existingByPath].sourceId else deriveSourceId(normalized, mode)

            if (existingByPath < 0 && entries.any { it.sourceId == resolvedId }) {
                throw IllegalArgumentException("Dev source ID already exists: $resolvedId")
            }

            val updated = StoredSource(
                sourceId = resolvedId,
                source = normalized.toString(),
                mode = mode.name,
                profileId = effectiveProfile,
                status = if (existingByPath >= 0) entries[existingByPath].status else "registered",
                packageIds = if (existingByPath >= 0) entries[existingByPath].packageIds else emptyList(),
            )
            if (existingByPath >= 0) {
                entries[existingByPath] = updated
            } else {
                entries.add(updated)
            }
            writeAll(entries)
            updated.toApi()
        }
    }

    fun updatePackageIds(sourceId: String, packageIds: List<String>, status: String? = null): PluginDevSource =
        synchronized(lock) {
            val entries = readAll().toMutableList()
            val index = entries.indexOfFirst { it.sourceId == sourceId }
            if (index < 0) throw PluginDevException("dev_source_missing", "Unknown dev source: $sourceId")
            val current = entries[index]
            val next = current.copy(
                packageIds = packageIds.distinct(),
                status = status ?: current.status,
            )
            entries[index] = next
            writeAll(entries)
            next.toApi()
        }

    fun detach(sourceId: String): Boolean = synchronized(lock) {
        val entries = readAll().toMutableList()
        val removed = entries.removeIf { it.sourceId == sourceId }
        if (removed) writeAll(entries)
        removed
    }

    fun <T> withSourceLock(sourceId: String, action: () -> T): T {
        val sourceLock = buildLocks.computeIfAbsent(sourceId) { ReentrantLock() }
        sourceLock.lock()
        try {
            return action()
        } finally {
            sourceLock.unlock()
        }
    }

    private fun deriveSourceId(normalized: Path, mode: PluginDevSourceMode): String {
        val key = "${mode.name}:${normalized.toString().replace('\\', '/')}"
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
        return "src-${digest.take(12)}"
    }

    private fun readAll(): List<StoredSource> {
        if (!Files.isRegularFile(file)) return emptyList()
        val text = Files.readString(file)
        if (text.isBlank()) return emptyList()
        return codec.decodeFromString(ListSerializer(StoredSource.serializer()), text)
    }

    private fun writeAll(entries: List<StoredSource>) {
        Files.createDirectories(file.parent)
        val temp = file.parent.resolve(".${file.fileName}.tmp-${System.nanoTime()}")
        Files.writeString(temp, codec.encodeToString(ListSerializer(StoredSource.serializer()), entries))
        try {
            Files.move(temp, file, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, file, REPLACE_EXISTING)
        }
    }
}
