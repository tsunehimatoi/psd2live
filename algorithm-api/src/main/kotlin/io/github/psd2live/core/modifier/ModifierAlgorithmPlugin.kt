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
data class ModifierPluginParameter(
    val id: String,
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val group: String? = null,
    val blend: Boolean = false,
    val repeat: Boolean = false,
) {
    fun definition(i18n: ModifierParameterI18n = ModifierParameterI18n.Empty) =
        io.github.psd2live.format.model.Parameter(
            id = id,
            name = i18n.resolveName(id, name),
            min = min,
            max = max,
            default = default,
            blend = blend,
            repeat = repeat,
        )

    fun resolvedGroup(i18n: ModifierParameterI18n = ModifierParameterI18n.Empty): String? =
        group?.let { i18n.resolveGroup(it) }
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
    /** Explicit owned object kinds created by this algorithm (e.g. parameters, warpDeformers, physicsGroups, derivedMeshes). */
    val creates: Set<String> = emptySet(),
    /** Explicit target channels modified by this algorithm (e.g. mesh.offsets, mesh.opacity, deformer.keyforms). */
    val modifies: Set<String> = emptySet(),
    val composableKinds: Set<String> = emptySet(),
    val configSchema: JsonObject,
    val defaults: JsonObject,
    val parameters: List<ModifierPluginParameter> = emptyList(),
    /** Stable document types retained by a bundled algorithm after moving into a package. */
    val aliases: Set<String> = emptySet(),
    /** Deterministic legacy names derived from an instance-owned output token. */
    val derivedOutputs: List<ModifierPluginDerivedOutput> = emptyList(),
    /** 2 opts into explicit upstream capability snapshots; existing packages retain context version 1. */
    val contextVersion: Int = 1,
    val provides: Set<String> = emptySet(),
    /** Optional host-captured author context contract (e.g. "face.skin-clip") for standalone plugins. */
    val authorContext: String? = null,
)

@Serializable
data class ModifierPluginDerivedOutput(
    val kind: String,
    val localName: String,
    val prefix: String,
    val suffixes: Set<String> = setOf(""),
    /** Also allow canonical numbered siblings _2 through _10000. */
    val numbered: Boolean = false,
) {
    init {
        require(localName.isNotBlank() && prefix.length <= 128 && suffixes.size in 1..32)
        require((listOf(localName, prefix) + suffixes).all { it.length <= 256 && it.none(Char::isISOControl) })
    }

    fun matches(instance: RigModifierInstance, outputKind: String, id: String): Boolean {
        if (kind != outputKind) return false
        val base = prefix + instance.outputId(localName)
        if (!id.startsWith(base)) return false
        val suffix = id.removePrefix(base)
        if (suffix in suffixes) return true
        if (!numbered || !suffix.startsWith("_")) return false
        val index = suffix.drop(1).toIntOrNull() ?: return false
        return index in 2..10000 && suffix == "_$index"
    }
}

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
