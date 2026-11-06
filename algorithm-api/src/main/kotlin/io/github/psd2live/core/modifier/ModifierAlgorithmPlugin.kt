package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Immutable identity captured by a document, never resolved to a newer package at replay time. */
@Serializable
data class ModifierPluginReference(val id: String, val version: String, val digest: String,
    val dependencyDigests: Map<String, String> = emptyMap())

/** Bounds are inclusive at minimum and exclusive at maximum; exact cannot be combined with bounds. */
@Serializable
data class ModifierPluginVersionRange(val exact: String? = null, val minimum: String? = null, val maximum: String? = null)

@Serializable
data class ModifierPluginDependency(val id: String, val version: ModifierPluginVersionRange = ModifierPluginVersionRange())

/** Execution is independent of packaging: bundled algorithms do not pay an IPC or serialization cost. */
fun interface ModifierAlgorithmPlugin {
    fun evaluate(input: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR
}

@Serializable
data class ModifierPluginParameter(val id: String, val name: String, val min: Float, val max: Float, val default: Float) {
    fun definition() = io.github.psd2live.format.model.Parameter(id, name, min, max, default)
}

@Serializable
data class ModifierPluginAlgorithm(
    val type: String,
    val version: Int = 1,
    val name: String,
    val category: String = "plugins",
    val targets: Set<String> = setOf("mesh"),
    val requires: Set<String> = emptySet(),
    val exclusiveFamily: String? = null,
    val reads: Set<String> = setOf("*"),
    val writes: Set<String> = setOf("mesh"),
    val composableKinds: Set<String> = emptySet(),
    val configSchema: JsonObject,
    val defaults: JsonObject,
    val parameters: List<ModifierPluginParameter> = emptyList(),
    /** Stable document types retained by a bundled algorithm after moving into a package. */
    val aliases: Set<String> = emptySet(),
)

@Serializable
data class ModifierPluginManifest(
    val protocol: Int = 1,
    val id: String,
    val version: String,
    val name: String,
    /** Platform keys are windows-x86_64, linux-x86_64, macos-aarch64, etc. */
    val commands: Map<String, List<String>>,
    /** SHA-256 of every payload file; the manifest itself is hashed as part of the package identity. */
    val files: Map<String, String>,
    val algorithms: List<ModifierPluginAlgorithm>,
    val dependencies: List<ModifierPluginDependency> = emptyList(),
    val conflicts: List<ModifierPluginDependency> = emptyList(),
)
