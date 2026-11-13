package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.*
import io.github.psd2live.core.Side
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import kotlinx.serialization.json.*

/** Explicit source-only neck candidate. Capturing it never evaluates a native binding or motion stage. */
internal object RigNeckSourceModifier {
    private val observed = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
        .mapTo(linkedSetOf()) { it.type }
    private val algorithm = object : ReadingModifierAlgorithmPlugin by NeckV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val canonicalOptInType = NeckAlgorithm.TYPE
        override val optionalUpstream = observed
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition = RigNeckModifier.definition(source).copy(
        version = NeckV4Algorithm.VERSION,
        plugin = algorithm,
        authorContext = { requireNotNull(source).capture(it) },
    )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2 && !skeletonEnabled) { "Neck v4 requires a shared source scaffold without a skeleton" }
        val scaffoldPackage = scaffold.plugin?.let { ModifierPluginVersion.parse(it.version, legacy = true) }
        require(scaffoldPackage == null || scaffoldPackage >= ModifierPluginVersion.parse("1.0.41")) {
            "Neck v4 requires a source-aware shared scaffold package"
        }
        val prefix = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains) && it.type in observed
        }
        val faceId = RigLegacyBindings.providers(instance).getValue("face/legacy")
        require(prefix.singleOrNull { it.id == faceId }?.let {
            NeckV4Algorithm.supportedFace(it.type, it.version) && it.targets.containsAll(instance.targets)
        } == true) { "Neck v4 requires its configured face provider on the complete target scope" }
        require(prefix.filterNot { it.type == NeckAlgorithm.TYPE }.all { it.targets.containsAll(instance.targets) }) {
            "Neck v4 requires shared preceding source configuration"
        }
        require(prefix.count { it.type == "body/legacy" } <= 1) { "Neck v4 requires an unambiguous body provider" }
        require((listOf(scaffold) + prefix).all { it.plugin == null || BuiltinModifierPlugins.matches(it) }) {
            "Neck v4 requires known built-in source providers"
        }
        val earlier = prefix.filter { it.type == NeckAlgorithm.TYPE }.flatMapTo(hashSetOf()) { it.targets }
        val targets = instance.targets.filterNot(earlier::contains).associate { it.id to layersByMesh.getValue(it.id) }
        val native = RigFittedMouthModuleSource.nativeConfig(this, scaffold, prefix, null).copy(generateDeformers = false)
        val measured = RigBuilder.rigContext(analysis, native, meshCache)
        val structure = RigBuilder.scaffoldSourceInputs(analysis, native, meshCache)
        val neck = RigBuilder.neckSourceInputs(analysis, atlas, native, meshCache, targets, primaryByLayer) { _, parent -> parent }
        val source = NeckInputs.decode(neck)
        require(source.targets.values.all { it.parent == ScaffoldHead.breathId.raw && it.frame == measured.bodyFrame }) {
            "Neck v4 supports only source necks under the body breath frame"
        }
        val layers = measured.analysis.layers.associateBy { it.source.id.raw }
        require(source.targets.keys.all { id -> layers.getValue(layersByMesh.getValue(id)).semantic.side == Side.NONE }) {
            "Neck v4 does not support automatically paired neck carriers"
        }
        val body = LegacyBodyInputs.capture(ModifierAlgorithmContext(buildJsonObject {
            put("schema", "scaffold/5"); put("frames", structure.frames)
        }), structure.torso, native.rigTuning.bodyMotion(), emptyMap(), emptyMap()).values
        val owners = listOf(scaffold) + prefix
        RigBuildProfile.count("modifier neck source measurements")
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", NeckV4Algorithm.SCHEMA)
            put("neck", neck.values)
            put("frames", structure.frames)
            put("body", JsonObject(body.filterKeys { it in setOf("torso", "tuning") } +
                ("strength" to JsonPrimitive(native.bodyStrength))))
            put("shell", if (RigModules.builds(RigModules.FACE_DEPTH_TURN, native, RecipeSubject.of(measured.analysis)))
                JsonArray(RigBuilder.headTurnMeasurements(measured.faceRig, native).map(::JsonPrimitive)) else JsonNull)
            put("providers", JsonArray(owners.map { owner -> Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(),
                ModifierUpstreamCapability(owner.type, owner.type, owner.version, owner.targets, owner.id,
                    owner.config, owner.outputAliases, owner.plugin)) }))
            putJsonObject("materials") {
                for (id in source.targets.keys) putJsonObject(id) {
                    val layer = layers.getValue(layersByMesh.getValue(id))
                    put("name", layer.source.name)
                    put("opacity", layer.source.opacity)
                }
            }
        }, neck.resources)
    }
}
