package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated store for development plugin artifacts.
 *
 * Candidate artifacts carry a TTL and can be pruned once unleased. Adopted artifacts are immutable,
 * have `expiresAt = null`, and are never auto-deleted so undo/redo/reopen keep working even if the
 * original source directory is moved or deleted. Dev artifacts never enter `ModifierPluginStore.defaultPackages()`
 * and never bump `ModifierPluginStore.revision`.
 */
internal object PluginDevArtifactStore {
    const val DEFAULT_CANDIDATE_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class StoredArtifactMetadata(
        val artifactId: String,
        val sourceId: String,
        val buildDigest: String,
        val packages: List<ModifierPluginReference>,
        val retention: PluginDevRetention,
        val expiresAt: Long? = null,
        val adoptedProjects: Set<String> = emptySet(),
    )

    internal class Store(val root: Path) {
        private val lock = Any()
        private val leases = ConcurrentHashMap<String, Int>()
        private val entries = LinkedHashMap<String, Pair<PluginDevArtifact, Set<String>>>()

        init {
            loadExisting()
        }

        fun seal(
            stagedDir: Path,
            sourceId: String,
            dependencyLock: List<ModifierPluginReference> = emptyList(),
            ttlMillis: Long = DEFAULT_CANDIDATE_TTL_MILLIS,
        ): PluginDevArtifact {
            require(ttlMillis > 0) { "Candidate TTL must be positive" }
            require(Files.isDirectory(stagedDir)) { "Staged artifact directory does not exist: $stagedDir" }
            val checked = ModifierPluginStore.inspectPackageDirectory(stagedDir)
            val dependencyDigests = dependencyLock.associate { "${it.id}@${it.version}" to it.digest }
            val reference = checked.reference.copy(dependencyDigests = dependencyDigests)
            val buildDigest = computeBuildDigest(checked.reference.digest, dependencyDigests)
            val artifactId = "art_${buildDigest.take(24)}"
            val now = System.currentTimeMillis()
            synchronized(lock) {
                entries[artifactId]?.let { (existing, projects) ->
                    val refreshed = if (existing.retention == PluginDevRetention.ADOPTED) {
                        existing
                    } else {
                        existing.copy(expiresAt = now + ttlMillis)
                    }
                    if (refreshed != existing) {
                        writeMetadata(artifactDir(artifactId), refreshed, projects)
                        entries[artifactId] = refreshed to projects
                    }
                    return refreshed
                }
                Files.createDirectories(root)
                val targetDir = artifactDir(artifactId)
                val tempDir = Files.createTempDirectory(root, ".seal-")
                try {
                    val payloadDir = tempDir.resolve("payload")
                    copyTree(stagedDir, payloadDir)
                    if (dependencyLock.isNotEmpty()) {
                        Files.writeString(
                            payloadDir.resolve(".dependencies"),
                            codec.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(ModifierPluginReference.serializer()),
                                dependencyLock,
                            ),
                        )
                    }
                    val artifact = PluginDevArtifact(
                        artifactId = artifactId,
                        sourceId = sourceId,
                        buildDigest = buildDigest,
                        packages = listOf(reference),
                        manifest = checked.manifest,
                        directory = targetDir.resolve("payload"),
                        retention = PluginDevRetention.CANDIDATE,
                        expiresAt = now + ttlMillis,
                    )
                    writeMetadata(tempDir, artifact, emptySet())
                    moveDirectory(tempDir, targetDir)
                    entries[artifactId] = artifact to emptySet()
                    return artifact
                } catch (failure: Throwable) {
                    runCatching { ModifierPluginStore.deleteTree(tempDir) }
                    throw failure
                }
            }
        }

        fun adopt(artifactId: String, projectId: String): PluginDevArtifact = synchronized(lock) {
            val (current, projects) = entries[artifactId]
                ?: throw PluginDevException(PluginDevErrorCodes.ARTIFACT_MISSING, "Dev artifact not found: $artifactId")
            val updatedProjects = projects + projectId
            val adopted = current.copy(retention = PluginDevRetention.ADOPTED, expiresAt = null)
            if (adopted != current || updatedProjects != projects) {
                writeMetadata(artifactDir(artifactId), adopted, updatedProjects)
                entries[artifactId] = adopted to updatedProjects
            }
            adopted
        }

        fun adoptByReference(reference: ModifierPluginReference, projectId: String): PluginDevArtifact? = synchronized(lock) {
            val match = entries.values.firstOrNull { (artifact, _) ->
                artifact.packages.any { matchesReference(it, reference) }
            }?.first ?: return null
            adopt(match.artifactId, projectId)
        }

        fun get(artifactId: String): PluginDevArtifact? = synchronized(lock) {
            val (artifact, _) = entries[artifactId] ?: return null
            if (isExpired(artifact, System.currentTimeMillis()) && !isLeased(artifactId)) return null
            artifact
        }

        fun resolve(reference: ModifierPluginReference): InstalledModifierPlugin? = synchronized(lock) {
            val now = System.currentTimeMillis()
            val match = entries.values.asSequence()
                .map { it.first }
                .filter { !isExpired(it, now) || isLeased(it.artifactId) }
                .firstOrNull { artifact ->
                    artifact.packages.any { matchesReference(it, reference) }
                } ?: return null
            val resolvedRef = match.packages.first { matchesReference(it, reference) }
            val depsFile = match.directory.resolve(".dependencies")
            val depLock = if (Files.isRegularFile(depsFile)) {
                runCatching {
                    codec.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ModifierPluginReference.serializer()),
                        Files.readString(depsFile),
                    )
                }.getOrNull()
            } else null
            InstalledModifierPlugin(
                manifest = match.manifest,
                reference = resolvedRef,
                directory = match.directory,
                enabled = true,
                dependencyLock = depLock,
            )
        }

        fun list(sourceId: String? = null): List<PluginDevArtifact> = synchronized(lock) {
            val now = System.currentTimeMillis()
            entries.values.asSequence()
                .map { it.first }
                .filter { sourceId == null || it.sourceId == sourceId }
                .filter { !isExpired(it, now) || isLeased(it.artifactId) }
                .toList()
        }

        fun acquireLease(artifactId: String) {
            synchronized(lock) {
                leases[artifactId] = (leases[artifactId] ?: 0) + 1
            }
        }

        fun releaseLease(artifactId: String) {
            synchronized(lock) {
                val count = leases[artifactId] ?: return
                if (count <= 1) leases.remove(artifactId) else leases[artifactId] = count - 1
            }
        }

        fun pruneExpiredCandidates(nowMillis: Long = System.currentTimeMillis()): Int = synchronized(lock) {
            val expired = entries.values
                .map { it.first }
                .filter { isExpired(it, nowMillis) && !isLeased(it.artifactId) }
            expired.forEach { artifact ->
                entries.remove(artifact.artifactId)
                val trash = root.resolve(".pruned-${artifact.artifactId}-${System.nanoTime()}")
                runCatching {
                    moveDirectory(artifactDir(artifact.artifactId), trash)
                    ModifierPluginStore.deleteTree(trash)
                }
            }
            expired.size
        }

        private fun isLeased(artifactId: String): Boolean = (leases[artifactId] ?: 0) > 0

        private fun isExpired(artifact: PluginDevArtifact, nowMillis: Long): Boolean =
            artifact.retention == PluginDevRetention.CANDIDATE && artifact.expiresAt != null && artifact.expiresAt <= nowMillis

        private fun artifactDir(artifactId: String): Path = root.resolve(artifactId)

        private fun writeMetadata(dir: Path, artifact: PluginDevArtifact, projects: Set<String>) {
            val meta = StoredArtifactMetadata(
                artifactId = artifact.artifactId,
                sourceId = artifact.sourceId,
                buildDigest = artifact.buildDigest,
                packages = artifact.packages,
                retention = artifact.retention,
                expiresAt = artifact.expiresAt,
                adoptedProjects = projects,
            )
            Files.writeString(dir.resolve("artifact.json"), codec.encodeToString(StoredArtifactMetadata.serializer(), meta))
        }

        private fun loadExisting() {
            if (!Files.isDirectory(root)) return
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.forEach { dir ->
                    runCatching {
                        val metaFile = dir.resolve("artifact.json")
                        val payloadDir = dir.resolve("payload")
                        if (!Files.isRegularFile(metaFile) || !Files.isDirectory(payloadDir)) return@runCatching
                        val meta = codec.decodeFromString(StoredArtifactMetadata.serializer(), Files.readString(metaFile))
                        val checked = ModifierPluginStore.inspectPackageDirectory(payloadDir)
                        val depDigests = meta.packages.firstOrNull()?.dependencyDigests.orEmpty()
                        val expectedBuildDigest = computeBuildDigest(checked.reference.digest, depDigests)
                        require(
                            meta.buildDigest == expectedBuildDigest ||
                                meta.buildDigest == checked.reference.digest,
                        ) { "Dev artifact digest mismatch: ${meta.artifactId}" }
                        val artifact = PluginDevArtifact(
                            artifactId = meta.artifactId,
                            sourceId = meta.sourceId,
                            buildDigest = meta.buildDigest,
                            packages = meta.packages,
                            manifest = checked.manifest,
                            directory = payloadDir,
                            retention = meta.retention,
                            expiresAt = meta.expiresAt,
                        )
                        entries[meta.artifactId] = artifact to meta.adoptedProjects
                    }
                }
            }
        }

        private fun copyTree(source: Path, destination: Path) {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    val name = source.relativize(path).toString().replace('\\', '/')
                    if (name.isEmpty()) {
                        Files.createDirectories(destination)
                        return@forEach
                    }
                    require(!Files.isSymbolicLink(path)) { "Plugin symlinks are not supported" }
                    val target = ModifierPluginStore.inside(destination, name)
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target)
                        if (Files.isExecutable(path)) target.toFile().setExecutable(true)
                    }
                }
            }
        }

        private fun matchesReference(candidate: ModifierPluginReference, requested: ModifierPluginReference): Boolean =
            candidate.id == requested.id &&
                candidate.version == requested.version &&
                candidate.digest == requested.digest &&
                candidate.dependencyDigests == requested.dependencyDigests

        private fun computeBuildDigest(payloadDigest: String, dependencyDigests: Map<String, String>): String {
            if (dependencyDigests.isEmpty()) return payloadDigest
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(payloadDigest.toByteArray(Charsets.UTF_8))
            for ((key, value) in dependencyDigests.entries.sortedBy { it.key }) {
                digest.update(0)
                digest.update(key.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(value.toByteArray(Charsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun moveDirectory(source: Path, destination: Path) {
            for (attempt in 0..6) {
                try {
                    Files.move(source, destination, ATOMIC_MOVE)
                    return
                } catch (failure: java.nio.file.FileSystemException) {
                    if (!ModifierPluginStore.platform.startsWith("windows-") || attempt == 6) throw failure
                    Thread.sleep(10L shl attempt)
                }
            }
        }
    }

    val shared by lazy {
        Store(Path.of(System.getProperty("user.home"), ".psd2live", "dev-artifacts"))
    }

    fun seal(
        stagedDir: Path,
        sourceId: String,
        dependencyLock: List<ModifierPluginReference> = emptyList(),
        ttlMillis: Long = DEFAULT_CANDIDATE_TTL_MILLIS,
    ): PluginDevArtifact = shared.seal(stagedDir, sourceId, dependencyLock, ttlMillis)

    fun adopt(artifactId: String, projectId: String): PluginDevArtifact = shared.adopt(artifactId, projectId)

    fun adoptByReference(reference: ModifierPluginReference, projectId: String): PluginDevArtifact? =
        shared.adoptByReference(reference, projectId)

    fun get(artifactId: String): PluginDevArtifact? = shared.get(artifactId)

    fun resolve(reference: ModifierPluginReference): InstalledModifierPlugin? = shared.resolve(reference)

    fun list(sourceId: String? = null): List<PluginDevArtifact> = shared.list(sourceId)

    fun acquireLease(artifactId: String) = shared.acquireLease(artifactId)

    fun releaseLease(artifactId: String) = shared.releaseLease(artifactId)

    fun pruneExpiredCandidates(nowMillis: Long = System.currentTimeMillis()): Int =
        shared.pruneExpiredCandidates(nowMillis)
}
