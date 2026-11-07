package io.github.psd2live.core.modifier

import kotlinx.serialization.json.Json
import java.nio.file.Files

/** Full executable packages shipped with the app and retained before any new document pins them. */
internal object BundledAlgorithmArtifacts {
    data class Artifact(val resource: String, val bytes: ByteArray, val manifest: ModifierPluginManifest) {
        val reference = ModifierPluginReference(manifest.id, manifest.version, ModifierPluginStore.hash(
            buildString {
                append(ModifierPluginStore.hash(bytes))
                manifest.files.toSortedMap().forEach { (path, hash) -> append('\n').append(path).append(':').append(hash) }
            }.encodeToByteArray()))
    }
    val artifacts: List<Artifact> by lazy {
        listOf("physics", "motion", "swing", "simulation", "binding", "see-through-eye", "see-through-brow",
            "skin-clip", "scaffold-1", "scaffold-2", "mouth-legacy", "hair-chains", "face-neck", "body-bust",
            "hair-legacy", "eye-lid-fade", "eye-fitted", "mouth-fitted-v2", "fitted-face-v2", "eye-squash", "body-legacy-v2",
            "mouth-outline-v2", "brow-fitted-v2", "eye-legacy").map { name ->
            val resource = "/algorithm-plugins/$name/"
            val bytes = requireNotNull(javaClass.getResourceAsStream(resource + "plugin.json")) { "Missing bundled algorithm: $name" }.use { it.readAllBytes() }
            Artifact(resource, bytes, Json.decodeFromString(ModifierPluginManifest.serializer(), bytes.decodeToString()))
        }
    }
    fun find(type: String, version: Int): Artifact? = artifacts.singleOrNull { artifact ->
        artifact.manifest.algorithms.any { it.version == version && type in it.aliases }
    }
    fun retain(store: ModifierPluginStore) {
        for (artifact in artifacts) {
            if (store.snapshot().any { it.reference == artifact.reference }) continue
            Files.createDirectories(store.root)
            val staging = Files.createTempDirectory(store.root, ".bundled-")
            try {
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                artifact.manifest.files.forEach { (name, expected) ->
                    val bytes = requireNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { it.readAllBytes() }
                    require(ModifierPluginStore.hash(bytes) == expected) { "Bundled algorithm checksum mismatch: $name" }
                    val path = ModifierPluginStore.inside(staging, name)
                    Files.createDirectories(path.parent); Files.write(path, bytes)
                }
                require(store.install(staging.toString()).reference == artifact.reference)
            } finally { ModifierPluginStore.deleteTree(staging) }
        }
    }
}
