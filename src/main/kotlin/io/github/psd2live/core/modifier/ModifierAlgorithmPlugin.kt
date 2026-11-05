package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Immutable identity captured by a document, never resolved to a newer package at replay time. */
@Serializable
internal data class ModifierPluginReference(val id: String, val version: String, val digest: String)

/** Execution is independent of packaging: bundled algorithms do not pay an IPC or serialization cost. */
internal fun interface ModifierAlgorithmPlugin {
    fun evaluate(input: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR
}

@Serializable
internal data class ModifierPluginAlgorithm(
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
)

@Serializable
internal data class ModifierPluginManifest(
    val protocol: Int = 1,
    val id: String,
    val version: String,
    val name: String,
    /** Platform keys are windows-x86_64, linux-x86_64, macos-aarch64, etc. */
    val commands: Map<String, List<String>>,
    /** SHA-256 of every payload file; the manifest itself is hashed as part of the package identity. */
    val files: Map<String, String>,
    val algorithms: List<ModifierPluginAlgorithm>,
)
