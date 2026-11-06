package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/** Individually versioned, in-process entries. Null document references remain legacy aliases, never rewritten on read. */
internal object BuiltinModifierPlugins {
    data class Entry(val reference: ModifierPluginReference, val definition: RigModifierDefinition)

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        SwingAlgorithmPlugin.definition, SimulationAlgorithmPlugin.definition,
        PhysicsAlgorithmPlugin.definition, MotionAlgorithmPlugin.definition,
    ) + RigSourceModifiers.definitions(source)

    private val implementations by lazy {
        listOf(SwingAlgorithmPlugin::class.java, SimulationAlgorithmPlugin::class.java,
            PhysicsAlgorithmPlugin::class.java, MotionAlgorithmPlugin::class.java,
            RigSourceModifiers::class.java, RigLegacyBindings::class.java, RigSourceBinding::class.java)
            .associateWith { implementation ->
                implementation.getResourceAsStream("${implementation.simpleName}.class")!!.use { ModifierPluginStore.hash(it.readAllBytes()) }
            }
    }

    val entries: List<Entry> by lazy {
        definitions(null).map { definition ->
            val implementation = when (definition.type) {
                "swing" -> SwingAlgorithmPlugin::class.java
                "simulation" -> SimulationAlgorithmPlugin::class.java
                "physics" -> PhysicsAlgorithmPlugin::class.java
                "motion" -> MotionAlgorithmPlugin::class.java
                else -> RigSourceModifiers::class.java
            }
            val code = if (implementation == RigSourceModifiers::class.java)
                listOf(implementation, RigLegacyBindings::class.java, RigSourceBinding::class.java).map { implementations.getValue(it) }
            else listOf(implementations.getValue(implementation))
            val id = "builtin." + java.util.HexFormat.of().formatHex(definition.type.encodeToByteArray()) + ".v${definition.version}"
            val version = "${definition.version}.0.0"
            Entry(ModifierPluginReference(id, version, ModifierPluginStore.hash(
                (listOf(id, version) + code).joinToString("\n").encodeToByteArray())), definition)
        }
    }
    private val byAlgorithm by lazy { entries.associateBy { it.definition.type to it.definition.version } }
    fun entry(type: String, version: Int): Entry? = byAlgorithm[type to version]
    fun entry(id: String): Entry? = entries.firstOrNull { it.reference.id == id }
    fun reference(type: String, version: Int): ModifierPluginReference? = entry(type, version)?.reference
    fun matches(instance: RigModifierInstance): Boolean = instance.plugin != null && instance.plugin == reference(instance.type, instance.version)

    /** Bundled and installed entries participate in the same package dependency graph. */
    fun packages(store: ModifierPluginStore): List<InstalledModifierPlugin> = entries.map { entry ->
        val definition = entry.definition
        val manifest = ModifierPluginManifest(id = entry.reference.id, version = entry.reference.version,
            name = definition.type, commands = emptyMap(), files = emptyMap(), algorithms = listOf(
                ModifierPluginAlgorithm(definition.type, definition.version, definition.type,
                    requires = definition.requires, exclusiveFamily = definition.exclusiveFamily,
                    reads = setOf("*"), writes = setOf("*"), composableKinds = definition.composableKinds,
                    configSchema = buildJsonObject { put("type", "object") }, defaults = JsonObject(emptyMap()))))
        InstalledModifierPlugin(manifest, entry.reference, store.root, store.builtinEnabled(entry.reference.id))
    }
}
