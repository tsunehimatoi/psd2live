package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** Full executable packages shipped with the app. Preserve exact payloads used by a project or worker. */
internal object BundledAlgorithmArtifacts {
    /** The binding worker runtime, bundled once for every package built on it instead of once per package. */
    private const val SHARED_RUNTIME = "runtime.jar"
    data class Artifact(val resource: String, val bytes: ByteArray, val manifest: ModifierPluginManifest) {
        val reference = ModifierPluginReference(manifest.id, manifest.version, ModifierPluginStore.hash(
            buildString {
                append(ModifierPluginStore.hash(bytes))
                manifest.files.toSortedMap().forEach { (path, hash) -> append('\n').append(path).append(':').append(hash) }
            }.encodeToByteArray()))
        /** Opens one bundled package file, using the shared runtime for the packages that ship without their copy. */
        fun open(name: String): java.io.InputStream? = javaClass.getResourceAsStream(resource + name)
            ?: if (name == SHARED_RUNTIME) javaClass.getResourceAsStream("/algorithm-plugins/shared/$SHARED_RUNTIME") else null
    }
    val artifacts: List<Artifact> by lazy {
        val index = requireNotNull(javaClass.getResourceAsStream("/algorithm-plugins/index.txt")) {
            "Missing bundled algorithm index"
        }.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        require(index.isNotEmpty() && index.distinct() == index) { "Invalid bundled algorithm index" }
        index.map { name ->
            val resource = "/algorithm-plugins/$name/"
            val bytes = requireNotNull(javaClass.getResourceAsStream(resource + "plugin.json")) { "Missing bundled algorithm: $name" }.use { it.readAllBytes() }
            Artifact(resource, bytes, Json.decodeFromString(ModifierPluginManifest.serializer(), bytes.decodeToString()))
        }
    }
    fun find(type: String, version: Int): Artifact? = artifacts.singleOrNull { artifact ->
        artifact.manifest.algorithms.any { it.version == version && type in it.aliases }
    }
    fun retain(store: ModifierPluginStore, requested: List<Artifact> = artifacts) {
        val retained = store.snapshot().mapTo(HashSet()) { it.reference }
        val missing = requested.filterNot { it.reference in retained }
        if (missing.isEmpty()) return
        RigBuildProfile.stage("plugin load: retain bundled packages") {
            val sharedRuntime = HashMap<String, ByteArray>()
            for (artifact in missing) {
                Files.createDirectories(store.root)
                val staging = Files.createTempDirectory(store.root, ".bundled-")
                try {
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for ((name, digest) in artifact.manifest.files) {
                        val path = ModifierPluginStore.inside(staging, name)
                        Files.createDirectories(path.parent)
                        if (name == SHARED_RUNTIME) {
                            val bytes = sharedRuntime.getOrPut(digest) {
                                requireNotNull(artifact.open(name)) { "Missing bundled file ${artifact.resource}$name" }.use { it.readAllBytes() }
                            }
                            Files.write(path, bytes)
                        } else {
                            requireNotNull(artifact.open(name)) { "Missing bundled file ${artifact.resource}$name" }.use { Files.copy(it, path) }
                        }
                    }
                    store.installBundled(staging, artifact.reference)
                    RigBuildProfile.count("plugin load: bundled packages retained")
                } finally { ModifierPluginStore.deleteTree(staging) }
            }
        }
    }
}
