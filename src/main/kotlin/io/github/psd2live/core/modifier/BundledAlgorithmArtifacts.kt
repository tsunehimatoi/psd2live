package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
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
            "skin-clip", "scaffold-1", "scaffold-2", "mouth-legacy", "hair-chains", "face-neck", "body-bust", "outfit-skirt", "outfit-ribbons", "outfit-creature",
            "hair-legacy", "eye-lid-fade", "eye-fitted", "mouth-fitted-v2", "mouth-vowel", "mouth-capture", "mouth-jaw", "mouth-tongue", "mouth-depth", "mouth-spring", "fitted-face-v2", "eye-squash", "body-legacy-v2",
            "mouth-outline-v2", "brow-fitted-v2", "eye-legacy", "face-legacy-v2", "brow-legacy-v2", "depth-turn-v2",
            "eye-wide", "brow-shape", "eye-smile", "eye-fitted-v4", "brow-spring", "brow-blink", "eye-aftershock", "body-arms", "brow-lid", "eye-gaze-lid", "eye-emotion", "eye-highlight",
            "mouth-cheek-lid", "mouth-cheek-lid-v3", "cheek-v2", "gloom-v2", "gloom-v3").map { name ->
            val resource = "/algorithm-plugins/$name/"
            val bytes = requireNotNull(javaClass.getResourceAsStream(resource + "plugin.json")) { "Missing bundled algorithm: $name" }.use { it.readAllBytes() }
            Artifact(resource, bytes, Json.decodeFromString(ModifierPluginManifest.serializer(), bytes.decodeToString()))
        }
    }
    fun find(type: String, version: Int): Artifact? = artifacts.singleOrNull { artifact ->
        artifact.manifest.algorithms.any { it.version == version && type in it.aliases }
    }
    fun retain(store: ModifierPluginStore) = RigBuildProfile.stage("plugin load: retain bundled packages") {
        val retained = store.snapshot().mapTo(HashSet()) { it.reference }
        for (artifact in artifacts.filterNot { it.reference in retained }) {
            Files.createDirectories(store.root)
            val staging = Files.createTempDirectory(store.root, ".bundled-")
            try {
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                for (name in artifact.manifest.files.keys) {
                    val path = ModifierPluginStore.inside(staging, name)
                    Files.createDirectories(path.parent)
                    requireNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, path) }
                }
                store.installBundled(staging, artifact.reference)
            } finally { ModifierPluginStore.deleteTree(staging) }
        }
    }
}
