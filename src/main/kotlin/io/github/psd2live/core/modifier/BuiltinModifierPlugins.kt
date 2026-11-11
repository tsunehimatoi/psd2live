package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.HairChainsAlgorithm
import kotlinx.serialization.json.*

/** Individually versioned, in-process entries. Null document references remain legacy aliases, never rewritten on read. */
internal object BuiltinModifierPlugins {
    data class Entry(val reference: ModifierPluginReference, val definition: RigModifierDefinition,
                     val artifact: BundledAlgorithmArtifacts.Artifact? = null)

    // Fingerprint inputs from the existing host-only bindings, in their original order.
    // Compiler output and source line numbers must not change saved compatibility references.
    private val historicalBindingCode = listOf(
        "f7f0972749d16f38edbaf8efbebc89d34e08a2bd1c35c28c0a320e281d4a2b2b",
        "83da034405a01be0055bc59007c438750f78d5413c3a696a8e8c98f69c71280e",
        "7e491e41ee392e64214cc16ca34edaf8278eca8de4043b7cf2ff64bb2db1af97",
    )

    fun definitions(source: RigModifierSource?): List<RigModifierDefinition> = listOf(
        SwingAlgorithmPlugin.definition, SimulationAlgorithmPlugin.definition,
        PhysicsAlgorithmPlugin.definition, MotionAlgorithmPlugin.definition,
    ) + RigSourceModifiers.definitions(source) + listOf(
        hairChains(source), RigSkirtModifier.definition(source), RigRibbonsModifier.definition(source), RigRibbonsModifier.residualDefinition(source), RigRibbonsModifier.modernDefinition(source), RigRibbonsModifier.sharedFrameDefinition(source), RigCreatureModifier.definition(source), RigCreatureModifier.residualDefinition(source), RigNeckModifier.definition(source), RigNeckModifier.foldedDefinition(source), RigBustModifier.definition(source), RigBustModifier.fittedDefinition(source),
        RigHairLegacySource.definition(source), RigLidFadeModifier.definition(source), RigLidFadeModifier.definition(source, modern = true), RigFittedEyeDefinition.definition(source), RigFittedEyeDefinition.mixedDefinition(source),
        RigFittedEyeDefinition.modernDefinition(source),
        RigFittedMouthDefinition.definition(source), RigFittedMouthDefinition.modernDefinition(source), RigFittedMouthDefinition.closedDefinition(source), RigFittedMouthDefinition.closedShellDefinition(source), RigFittedMouthDefinition.sharedFrameDefinition(source), RigMouthVowelModifier.definition(source), RigMouthVowelModifier.outlineDefinition(source), RigMouthVowelModifier.fittedDefinition(source), RigMouthVowelModifier.closedDefinition(source), RigMouthVowelModifier.sourceOutlineDefinition(source), RigMouthVowelModifier.sharedFrameDefinition(source), RigMouthCaptureModifier.definition(source), RigMouthCaptureModifier.fittedDefinition(source), RigMouthCaptureModifier.closedDefinition(source), RigMouthCaptureModifier.modernDefinition(source), RigMouthCaptureModifier.sharedFrameDefinition(source), RigMouthJawModifier.definition(source), RigMouthJawModifier.fittedDefinition(source), RigMouthJawModifier.tongueDefinition(source), RigMouthJawModifier.closedDefinition(source), RigMouthJawModifier.closedTongueDefinition(source), RigMouthJawModifier.modernDefinition(source), RigMouthJawModifier.sharedFrameDefinition(source), RigMouthJawModifier.sharedFrameDefinition(source, withTongue = true), RigMouthTongueModifier.definition(source), RigMouthTongueModifier.fittedDefinition(source), RigMouthTongueModifier.closedDefinition(source), RigMouthTongueModifier.modernDefinition(source), RigMouthTongueModifier.sharedFrameDefinition(source), RigFittedFaceDefinition.definition(source),
        RigLegacyFaceDefinition.definition(source),
        RigFittedFaceDefinition.bodyDefinition(source),
        RigEyeSquashModifier.definition(source), RigEyeSquashModifier.definition(source, modern = true), RigEyeSquashModifier.highlightDefinition(source), RigEyeSquashModifier.sharedFrameDefinition(source), RigLegacyBodyDefinition.definition(source),
        RigMouthOutlineDefinition.definition(source), RigFittedMouthOutlineDefinition.definition(source), RigClassicMouthOutlineDefinition.definition(source), RigFittedMouthOutlineDefinition.closedDefinition(source), RigFittedMouthOutlineDefinition.closedShellDefinition(source), RigFittedMouthOutlineDefinition.orderedDefinition(source), RigFittedMouthOutlineDefinition.sharedFrameDefinition(source), RigBrowFittedModifier.definition(source), RigBrowFittedModifier.modernDefinition(source), RigBrowFittedModifier.sharedFrameDefinition(source),
        RigLegacyEyeDefinition.definition(source), RigLegacyBrowDefinition.definition(source),
        RigDepthTurnDefinition.definition(source), RigEyeWideModifier.definition(source), RigEyeWideModifier.modernDefinition(source), RigEyeWideModifier.sharedFrameDefinition(source),
        RigMouthSpringModifier.definition(source), RigMouthSpringModifier.fullDefinition(source), RigMouthSpringModifier.fittedDefinition(source), RigMouthSpringModifier.tongueDefinition(source), RigMouthSpringModifier.closedDefinition(source), RigMouthSpringModifier.closedDepthDefinition(source), RigMouthSpringModifier.modernDefinition(source), RigMouthSpringModifier.sharedFrameDefinition(source), RigMouthCheekLidModifier.definition(source), RigMouthCheekLidModifier.fittedDefinition(source), RigMouthCheekLidModifier.fittedMouthDefinition(source), RigMouthCheekLidModifier.fittedEyeDefinition(source), RigMouthCheekLidModifier.closedDefinition(source), RigMouthCheekLidModifier.closedShellDefinition(source), RigMouthCheekLidModifier.sharedFrameDefinition(source), RigMouthCheekLidModifier.openShellDefinition(source), RigMouthDepthModifier.definition(source), RigMouthDepthModifier.fittedDefinition(source), RigMouthDepthModifier.closedDefinition(source), RigMouthDepthModifier.closedShellDefinition(source), RigMouthDepthModifier.sharedFrameDefinition(source),
        RigBrowShapeModifier.definition(source), RigBrowShapeModifier.fittedDefinition(source), RigBrowShapeModifier.fallbackDefinition(source), RigBrowShapeModifier.sharedFrameDefinition(source), RigBrowShapeModifier.openShellDefinition(source), RigBrowSpringModifier.definition(source), RigBrowSpringModifier.composedDefinition(source), RigBrowSpringModifier.fittedDefinition(source), RigBrowSpringModifier.fallbackDefinition(source), RigBrowSpringModifier.sharedFrameDefinition(source), RigBrowLidModifier.definition(source), RigBrowLidModifier.fittedDefinition(source), RigBrowLidModifier.fittedEyeDefinition(source), RigBrowLidModifier.sidedDefinition(source), RigBrowLidModifier.fallbackDefinition(source), RigBrowLidModifier.sharedFrameDefinition(source), RigBrowLidModifier.singleEyeDefinition(source),
        RigCheekModifier.definition(source), RigCheekModifier.carrierDefinition(source), RigBrowBlinkModifier.definition(source), RigBrowBlinkModifier.fittedDefinition(source), RigBrowBlinkModifier.fallbackDefinition(source), RigBrowBlinkModifier.sharedFrameDefinition(source), RigEyeSmileModifier.definition(source), RigEyeSmileModifier.modernDefinition(source), RigEyeSmileModifier.sharedFrameDefinition(source),
        RigEyeAftershockModifier.definition(source), RigEyeAftershockModifier.modernDefinition(source), RigEyeAftershockModifier.fittedHeadDefinition(source), RigEyeAftershockModifier.sharedFrameDefinition(source), RigArmSwingModifier.definition(source), RigArmSwingModifier.definition(source, modern = true), RigArmSwingModifier.singleDefinition(source),
        RigEyeGazeLidModifier.definition(source), RigEyeGazeLidModifier.definition(source, modern = true), RigEyeGazeLidModifier.sharedFrameDefinition(source), RigEyeEmotionModifier.definition(source), RigEyeEmotionModifier.definition(source, modern = true), RigEyeEmotionModifier.sharedFrameDefinition(source), RigEyeHighlightModifier.definition(source), RigEyeHighlightModifier.definition(source, modern = true), RigEyeHighlightModifier.sharedFrameDefinition(source),
        RigGloomModifier.definition(source), RigGloomModifier.carrierDefinition(source), RigExpressionModifier.definition(source),
    )

    private fun hairChains(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec("hair/chains"), source)
        val algorithm = HairChainsAlgorithm()
        return legacy.copy(version = 2, parameters = { emptyList() }, upstreamInputs = true,
            changeWrites = HairChainsAlgorithm.WRITES, plugin = algorithm,
            authorContext = { requireNotNull(source).hairChainsContext(it) })
    }

    val entries: List<Entry> by lazy {
        definitions(null).map { definition ->
            BundledAlgorithmArtifacts.find(definition.type, definition.version)?.let { return@map Entry(it.reference, definition, it) }
            require(RigLegacyBindings.find(definition.type)?.version == definition.version) {
                "No bundled package for ${definition.type}@${definition.version}"
            }
            val id = "builtin." + java.util.HexFormat.of().formatHex(definition.type.encodeToByteArray()) + ".v${definition.version}"
            val version = "${definition.version}.0.0"
            Entry(ModifierPluginReference(id, version, ModifierPluginStore.hash(
                (listOf(id, version) + historicalBindingCode).joinToString("\n").encodeToByteArray())), definition)
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
