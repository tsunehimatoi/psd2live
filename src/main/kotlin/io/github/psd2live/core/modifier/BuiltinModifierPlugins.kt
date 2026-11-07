package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.HairChainsAlgorithm
import kotlinx.serialization.json.*

/** Individually versioned, in-process entries. Null document references remain legacy aliases, never rewritten on read. */
internal object BuiltinModifierPlugins {
    data class Entry(val reference: ModifierPluginReference, val definition: RigModifierDefinition,
                     val artifact: BundledAlgorithmArtifacts.Artifact? = null)

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
        hairChains(source), RigSkirtModifier.definition(source), RigRibbonsModifier.definition(source), RigCreatureModifier.definition(source), RigNeckModifier.definition(source), RigBustModifier.definition(source),
        RigHairLegacySource.definition(source), RigLidFadeModifier.definition(source), RigFittedEyeDefinition.definition(source), RigFittedEyeDefinition.mixedDefinition(source),
        RigFittedEyeDefinition.modernDefinition(source),
        RigFittedMouthDefinition.definition(source), RigFittedMouthDefinition.modernDefinition(source), RigMouthVowelModifier.definition(source), RigMouthVowelModifier.outlineDefinition(source), RigMouthVowelModifier.fittedDefinition(source), RigMouthCaptureModifier.definition(source), RigMouthCaptureModifier.fittedDefinition(source), RigMouthJawModifier.definition(source), RigMouthJawModifier.fittedDefinition(source), RigMouthJawModifier.tongueDefinition(source), RigMouthTongueModifier.definition(source), RigMouthTongueModifier.fittedDefinition(source), RigFittedFaceDefinition.definition(source),
        RigLegacyFaceDefinition.definition(source),
        RigFittedFaceDefinition.bodyDefinition(source),
        RigEyeSquashModifier.definition(source), RigLegacyBodyDefinition.definition(source),
        RigMouthOutlineDefinition.definition(source), RigFittedMouthOutlineDefinition.definition(source), RigBrowFittedModifier.definition(source), RigBrowFittedModifier.modernDefinition(source),
        RigLegacyEyeDefinition.definition(source), RigLegacyBrowDefinition.definition(source),
        RigDepthTurnDefinition.definition(source), RigEyeWideModifier.definition(source), RigEyeWideModifier.modernDefinition(source),
        RigMouthSpringModifier.definition(source), RigMouthSpringModifier.fullDefinition(source), RigMouthSpringModifier.fittedDefinition(source), RigMouthSpringModifier.tongueDefinition(source), RigMouthCheekLidModifier.definition(source), RigMouthCheekLidModifier.fittedDefinition(source), RigMouthCheekLidModifier.fittedMouthDefinition(source), RigMouthCheekLidModifier.fittedEyeDefinition(source), RigMouthDepthModifier.definition(source), RigMouthDepthModifier.fittedDefinition(source),
        RigBrowShapeModifier.definition(source), RigBrowSpringModifier.definition(source), RigBrowSpringModifier.composedDefinition(source), RigBrowLidModifier.definition(source), RigBrowLidModifier.fittedDefinition(source), RigBrowLidModifier.fittedEyeDefinition(source),
        RigCheekModifier.definition(source), RigBrowBlinkModifier.definition(source), RigBrowBlinkModifier.fittedDefinition(source), RigEyeSmileModifier.definition(source), RigEyeSmileModifier.modernDefinition(source),
        RigEyeAftershockModifier.definition(source), RigEyeAftershockModifier.modernDefinition(source), RigArmSwingModifier.definition(source), RigArmSwingModifier.definition(source, modern = true),
        RigEyeGazeLidModifier.definition(source), RigEyeGazeLidModifier.definition(source, modern = true), RigEyeEmotionModifier.definition(source), RigEyeEmotionModifier.definition(source, modern = true), RigEyeHighlightModifier.definition(source),
        RigGloomModifier.definition(source), RigGloomModifier.carrierDefinition(source),
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
            BundledAlgorithmArtifacts.find(definition.type, definition.version)?.let { return@map Entry(it.reference, definition, it) }
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
        val manifest = entry.artifact?.manifest ?: ModifierPluginManifest(id = entry.reference.id, version = entry.reference.version,
            name = definition.type, commands = emptyMap(), files = emptyMap(), algorithms = listOf(
                ModifierPluginAlgorithm(definition.type, definition.version, definition.type,
                    requires = definition.requires, provides = definition.provides, exclusiveFamily = definition.exclusiveFamily,
                    reads = setOf("*"), writes = setOf("*"), composableKinds = definition.composableKinds,
                    configSchema = buildJsonObject { put("type", "object") }, defaults = JsonObject(emptyMap()))))
        InstalledModifierPlugin(manifest, entry.reference, store.root, store.builtinEnabled(entry.reference.id))
    }
}
