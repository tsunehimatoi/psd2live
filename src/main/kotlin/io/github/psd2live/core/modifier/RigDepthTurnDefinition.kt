package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.DepthTurnV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigDepthTurnDefinition {
    private val algorithm = object : ChangingModifierAlgorithmPlugin by DepthTurnV2Algorithm,
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("body/legacy")
        override val canonicalOptInType = DepthTurnV2Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(DepthTurnV2Algorithm.TYPE), source).copy(
            version = DepthTurnV2Algorithm.VERSION,
            parameters = { emptyList() },
            evaluate = { _, _, _ -> error("Depth turn v2 requires captured source inputs") },
            plugin = algorithm,
            changeWrites = DepthTurnV2Algorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    fun requireCompatible(type: String, version: Int, targets: List<ModifierTarget>, existing: Sequence<ModifierCapability>) {
        val conflict: (ModifierCapability) -> Boolean = when {
            type == DepthTurnV2Algorithm.TYPE && version == DepthTurnV2Algorithm.VERSION -> { it -> it.type == RigLegacyBindings.FITTED_FACE }
            type == RigLegacyBindings.FITTED_FACE -> { it -> it.type == DepthTurnV2Algorithm.TYPE && it.version == DepthTurnV2Algorithm.VERSION }
            else -> return
        }
        require(existing.none { conflict(it) && it.targets.any(targets::contains) }) {
            "Depth turn v2 needs a legacy face binding and cannot be combined with fitted face"
        }
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val pinned = scaffold.plugin?.let { ModifierPluginVersion.parse(it.version, legacy = true) }
        require(scaffold.version == 2 && (pinned == null || pinned >= ModifierPluginVersion.parse("1.0.41"))) {
            "Depth turn v2 requires shared source scaffold v2"
        }
        val previous = activeBefore(instance).filter { earlier -> earlier.targets.any(instance.targets::contains) }
        val faceId = RigLegacyBindings.providers(instance).getValue("face/legacy")
        val face = previous.singleOrNull { it.id == faceId && it.type == "face/legacy" && it.version in 1..2 }
        val bodies = previous.filter { it.type == "body/legacy" && it.version in 1..2 }
        require(face != null && bodies.size <= 1 && previous.all { it == scaffold || it == face || it in bodies } &&
            (listOf(face) + bodies).all { it.config["scaffold"] == JsonPrimitive(scaffold.id) &&
                it.targets.toSet() == instance.targets.toSet() }) {
            "Depth turn v2 requires the legacy face and optional legacy body as its preceding source bindings"
        }
        val body = bodies.singleOrNull()
        val captured = scaffoldContext(scaffold)
        val frameInput = captured.values.getValue("frames").jsonObject
        val head = ScaffoldFramesContext.decode(frameInput).head
        val static = ScaffoldFramesContext.build(frameInput)
        val branches = ScaffoldBranchesContext.build(captured.values.getValue("branches").jsonObject, static)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val turnModule = RigModules.FACE_DEPTH_TURN
        val recipe = RigRecipe(true,
            bases = RigModules.areas.associate { it.id to BaseChoice(it.legacy.id, it.legacy.version) },
            modules = RigModules.modules.associate { module ->
                module.id to ModuleChoice(module == turnModule,
                    values = if (module == turnModule) RigLegacyBindings.values(instance) else emptyMap())
            }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val tuning = RigLegacyBindings.tuning(face, body?.let { RigLegacyBindings.tuning(it, RigTuning()) } ?: RigTuning())
        val native = sourceConfig().copy(generateDeformers = false, recipe = recipe, rigTuning = tuning,
            bodyStrength = body?.let(RigLegacyBindings::strength) ?: 1f, headTurnStrength = RigLegacyBindings.strength(face),
            featureDisplacementEnabled = head.featureDisplacement)
        val measured = RigBuilder.rigContext(analysis, native, meshCache)
        val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
        val layers = instance.targets.associate { target -> target.id to byLayer.getValue(layersByMesh.getValue(target.id)) }
        require(layers.values.any { it.semantic.tag == SemanticTag.FACE }) { "Depth turn v2 requires a face layer" }
        val parents = layers.mapValues { (_, layer) -> branches.parents.getValue(layer.source.id.raw).id }
        val frames = ScaffoldHead.build(head, { it }).frames + branches.parents.values.associate { it.id to it.frame }
        val turn = RigDepthTurnSource.capture(measured, native, layers, parents, frames,
            captured.resources.getValue("source_meshes"),
            layers.mapValues { (_, layer) -> primaryByLayer.getValue(layer.source.id.raw) }).encode()
        val frame = measured.faceRig
        val angles = StandardParameters.all.filter { it.id in RigLegacyBindings.spec("face/legacy").parameters }
        val base = LegacyFaceInputs.capture(ModifierAlgorithmContext(captured.values), frame.centerY, frame.radiusX,
            frame.radiusY, frame.eyeLineY, tuning.legacyFaceTuning(), native.headTurnStrength, angles.map { it.name },
            tr("model.group.face"))
        return ModifierAlgorithmContext(buildJsonObject {
            put("turn", turn.values)
            put("base", base.values)
            putJsonObject("parents") { parents.forEach { (mesh, parent) -> put(mesh, parent.raw) } }
            body?.let { put("body", it.id) }
        }, turn.resources)
    }
}
