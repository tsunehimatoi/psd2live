package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** Full executable packages shipped with the app. Preserve exact payloads used by a project or worker. */
internal object BundledAlgorithmArtifacts {
    data class Artifact(val resource: String, val bytes: ByteArray, val manifest: ModifierPluginManifest) {
        val reference = ModifierPluginReference(manifest.id, manifest.version, ModifierPluginStore.hash(
            buildString {
                append(ModifierPluginStore.hash(bytes))
                manifest.files.toSortedMap().forEach { (path, hash) -> append('\n').append(path).append(':').append(hash) }
            }.encodeToByteArray()))
    }
    val artifacts: List<Artifact> by lazy {
        listOf("physics", "motion", "swing", "simulation", "binding", "expression-v2", "see-through-eye", "see-through-brow",
            "skin-clip", "scaffold-1", "scaffold-2", "mouth-legacy", "hair-chains", "face-neck", "body-bust", "outfit-skirt", "outfit-ribbons", "outfit-creature",
            "hair-legacy", "eye-lid-fade", "eye-lid-fade-v3", "eye-fitted", "mouth-fitted-v2", "mouth-fitted-v3", "mouth-fitted-v4", "mouth-vowel", "mouth-vowel-v3", "mouth-vowel-v4", "mouth-capture", "mouth-capture-v3", "mouth-jaw", "mouth-jaw-v3", "mouth-jaw-v4", "mouth-tongue", "mouth-tongue-v3", "mouth-depth", "mouth-depth-v3", "mouth-spring", "mouth-spring-v3", "mouth-spring-v4", "mouth-spring-v5", "fitted-face-v2", "eye-squash", "eye-squash-v3", "eye-squash-v4", "body-legacy-v2",
            "mouth-outline-v2", "mouth-outline-v3", "mouth-outline-v4", "brow-fitted-v2", "brow-fitted-v3", "eye-legacy", "face-legacy-v2", "brow-legacy-v2", "depth-turn-v2",
            "eye-wide", "eye-wide-v3", "brow-shape", "brow-shape-v3", "eye-smile", "eye-smile-v3", "eye-fitted-v4", "eye-fitted-v5", "brow-spring", "brow-spring-v3", "brow-spring-v4", "brow-blink", "brow-blink-v3", "eye-aftershock", "eye-aftershock-v3", "eye-aftershock-v4", "body-arms", "body-arms-v3", "brow-lid", "brow-lid-v3", "brow-lid-v4", "eye-gaze-lid", "eye-gaze-lid-v3", "eye-emotion", "eye-emotion-v3", "eye-highlight", "eye-highlight-v3",
            "mouth-cheek-lid", "mouth-cheek-lid-v3", "mouth-cheek-lid-v4", "mouth-cheek-lid-v5", "cheek-v2", "gloom-v2", "gloom-v3", "fitted-face-v3").map { name ->
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
            for (artifact in missing) {
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
                    RigBuildProfile.count("plugin load: bundled packages retained")
                } finally { ModifierPluginStore.deleteTree(staging) }
            }
        }
    }
}
