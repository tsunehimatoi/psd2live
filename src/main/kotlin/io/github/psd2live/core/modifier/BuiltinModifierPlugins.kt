package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/** Individually versioned, in-process entries. Null document references remain legacy aliases, never rewritten on read. */
internal object BuiltinModifierPlugins {
    data class Entry(val reference: ModifierPluginReference, val definition: RigModifierDefinition,
                     val artifact: BundledAlgorithmArtifacts.Artifact? = null)

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        SwingAlgorithmPlugin.definition,
        SimulationAlgorithmPlugin.definition,
        PhysicsAlgorithmPlugin.definition,
        MotionAlgorithmPlugin.definition,
    ) + RigSourceModifiers.definitions(source)

    val entries: List<Entry> by lazy {
        definitions(null).map { definition ->
            val artifact = requireNotNull(BundledAlgorithmArtifacts.find(definition.type, definition.version)) {
                "No bundled package for ${definition.type}@${definition.version}"
            }
            Entry(artifact.reference, definition, artifact)
        }
    }

    private fun canonicalVersion(type: String, version: Int): Int =
        if (version > 1 && RigLegacyBindings.find(type) != null) 1 else version

    private fun isHistoricalBuiltinReference(type: String, version: Int, plugin: ModifierPluginReference): Boolean =
        version > 1 && RigLegacyBindings.find(type) != null &&
            plugin.id == "builtin." + java.util.HexFormat.of().formatHex(type.encodeToByteArray()) + ".v$version"

    private val byAlgorithm by lazy { entries.associateBy { it.definition.type to it.definition.version } }
    fun entry(type: String, version: Int): Entry? = byAlgorithm[type to canonicalVersion(type, version)]
    fun entry(id: String): Entry? = entries.firstOrNull { it.reference.id == id }
    fun reference(type: String, version: Int): ModifierPluginReference? = entry(type, version)?.reference
    fun matches(instance: RigModifierInstance): Boolean {
        val plugin = instance.plugin ?: return false
        return plugin == reference(instance.type, instance.version) ||
            isHistoricalBuiltinReference(instance.type, instance.version, plugin)
    }

    /** Bundled and installed entries participate in the same package dependency graph. */
    fun packages(store: ModifierPluginStore): List<InstalledModifierPlugin> = entries.map { entry ->
        val definition = entry.definition
        val manifest = entry.artifact?.manifest ?: ModifierPluginManifest(id = entry.reference.id, version = entry.reference.version,
            name = definition.type, commands = emptyMap(), files = emptyMap(), algorithms = listOf(
                ModifierPluginAlgorithm(definition.type, definition.version, definition.type,
                    requires = definition.requires, provides = definition.provides, exclusiveFamily = definition.exclusiveFamily,
                    reads = setOf("*"), writes = setOf("*"), composableKinds = definition.composableKinds,
                    configSchema = buildJsonObject { put("type", "object") }, defaults = JsonObject(emptyMap()))))
        InstalledModifierPlugin(manifest, entry.reference, store.root, store.builtinEnabled(entry.reference.id))
    }
}
