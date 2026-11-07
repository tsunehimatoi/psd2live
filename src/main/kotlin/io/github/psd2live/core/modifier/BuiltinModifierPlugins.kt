package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.HairChainsAlgorithm
import kotlinx.serialization.json.*

/** Individually versioned, in-process entries. Null document references remain legacy aliases, never rewritten on read. */
internal object BuiltinModifierPlugins {
    data class Entry(val reference: ModifierPluginReference, val definition: RigModifierDefinition)

    private val physicsCode = listOf(
        io.github.psd2live.algorithms.physics.PendulumAlgorithm::class.java,
        io.github.psd2live.algorithms.physics.PhysicsGroups::class.java,
        io.github.psd2live.core.RigPhysicsEdit::class.java,
        io.github.psd2live.core.PhysicsInput::class.java, io.github.psd2live.core.PhysicsOutput::class.java,
        io.github.psd2live.core.PhysicsSegment::class.java, io.github.psd2live.core.PhysicsNormalization::class.java,
        io.github.psd2live.core.PhysicsSourceType::class.java,
        Class.forName("io.github.psd2live.core.RigPhysicsKt"),
    ).flatMap { listOf(it) + it.declaredClasses.sortedBy { nested -> nested.name } }

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        SwingAlgorithmPlugin.definition, SimulationAlgorithmPlugin.definition,
        PhysicsAlgorithmPlugin.definition, MotionAlgorithmPlugin.definition,
    ) + RigSourceModifiers.definitions(source) + listOf(
        hairChains(source), RigSkirtModifier.definition(source), RigNeckModifier.definition(source), RigBustModifier.definition(source),
        RigHairLegacySource.definition(source), RigLidFadeModifier.definition(source), RigFittedEyeDefinition.definition(source), RigFittedEyeDefinition.mixedDefinition(source),
        RigFittedMouthDefinition.definition(source), RigFittedFaceDefinition.definition(source),
        RigLegacyFaceDefinition.definition(source),
        RigEyeSquashModifier.definition(source), RigLegacyBodyDefinition.definition(source),
        RigMouthOutlineDefinition.definition(source), RigBrowFittedModifier.definition(source),
        RigLegacyEyeDefinition.definition(source), RigLegacyBrowDefinition.definition(source),
        RigDepthTurnDefinition.definition(source), RigEyeWideModifier.definition(source),
        RigBrowShapeModifier.definition(source), RigBrowSpringModifier.definition(source),
        RigBrowBlinkModifier.definition(source), RigEyeSmileModifier.definition(source),
        RigEyeAftershockModifier.definition(source), RigArmSwingModifier.definition(source),
    )

    private fun hairChains(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec("hair/chains"), source)
        val algorithm = HairChainsAlgorithm()
        return legacy.copy(version = 2, parameters = { emptyList() }, upstreamInputs = true,
            changeWrites = HairChainsAlgorithm.WRITES, evaluate = algorithm::evaluate, plugin = algorithm,
            authorContext = { requireNotNull(source).hairChainsContext(it) })
    }

    private val implementations by lazy {
        listOf(SwingAlgorithmPlugin::class.java, SimulationAlgorithmPlugin::class.java,
            PhysicsAlgorithmPlugin::class.java, MotionAlgorithmPlugin::class.java,
            RigSourceModifiers::class.java, RigLegacyBindings::class.java, RigSourceBinding::class.java).plus(physicsCode)
            .associateWith { implementation ->
                implementation.getResourceAsStream("/${implementation.name.replace('.', '/')}.class")!!.use { ModifierPluginStore.hash(it.readAllBytes()) }
            }
    }

    val entries: List<Entry> by lazy {
        definitions(null).map { definition ->
            BundledAlgorithmArtifacts.find(definition.type, definition.version)?.let { return@map Entry(it.reference, definition) }
            val implementation = when (definition.type) {
                "swing" -> SwingAlgorithmPlugin::class.java
                "simulation" -> SimulationAlgorithmPlugin::class.java
                "physics" -> PhysicsAlgorithmPlugin::class.java
                "motion" -> MotionAlgorithmPlugin::class.java
                else -> RigSourceModifiers::class.java
            }
            val code = if (implementation == PhysicsAlgorithmPlugin::class.java)
                (listOf(implementation) + physicsCode).map { implementations.getValue(it) }
            else if (implementation == RigSourceModifiers::class.java)
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
