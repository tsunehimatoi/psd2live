package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.LegacyBodyInputs
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

/** Source observations for the head before and after its fitted-eye landmarks are available. */
internal object RigFittedEyeHeadSource {
    fun capture(owner: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext? {
        val scaffold = owner.provider(instance)
        val prefix = owner.activeBefore(instance).filter { it.config["scaffold"] == JsonPrimitive(scaffold.id) &&
            it.targets.any(instance.targets::contains) }
        val head = prefix.singleOrNull { it.type == "face/fitted" } ?: return null
        val body = prefix.singleOrNull { it.type == "body/legacy" }
        require(instance.targets.isNotEmpty() && instance.targets.toSet() == scaffold.targets.toSet())
        require(scaffold.version == 2 && prefix.all { (it == body || it == head) && it.version == 1 } &&
            prefix.all { it.targets.toSet() == scaffold.targets.toSet() }) {
            "Fitted eye v4 requires a shared v2 scaffold with the compatible fitted face and body"
        }
        val ordinary = owner.scaffoldContext(scaffold)
        val canonicalTargets = ordinary.values.getValue("canonical_targets").jsonObject
        val canonicalByLayer = canonicalTargets.entries.associate { owner.layersByMesh.getValue(it.key) to it.value.jsonPrimitive.content }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true,
            bases = RigModules.areas.associate { it.id to BaseChoice(it.legacy.id, it.legacy.version) },
            modules = RigModules.modules.associate {
                val enabled = it == RigModules.FACE_DEPTH_TURN
                it.id to ModuleChoice(enabled, it.version, if (enabled) RigLegacyBindings.values(head) else emptyMap())
            }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = owner.sourceConfig().copy(recipe = recipe,
            bodyStrength = body?.let(RigLegacyBindings::strength) ?: 1f,
            headTurnStrength = RigLegacyBindings.strength(head),
            rigTuning = RigLegacyBindings.tuning(head, body?.let { RigLegacyBindings.tuning(it, RigTuning()) } ?: RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        )
        val fitted = native.copy(recipe = recipe.copy(bases = recipe.bases +
            ("eye" to BaseChoice(instance.type, instance.version, RigLegacyBindings.values(instance)))),
            rigTuning = RigLegacyBindings.tuning(instance, native.rigTuning))
        val parameters = RigLegacyBindings.spec(head.type).parameters
        fun phase(config: PipelineConfig): Pair<ModifierAlgorithmContext, ModifierAlgorithmContext?> {
            val structure = RigBuilder.scaffoldSourceInputs(owner.analysis, config, owner.meshCache)
            val meshes = RigBuilder.scaffoldMeshInputs(owner.analysis, owner.atlas, config, owner.meshCache, canonicalByLayer)
            val captured = ModifierAlgorithmContext(JsonObject(ordinary.values + mapOf(
                "frames" to structure.frames, "branches" to structure.branches, "mesh_inputs" to meshes.values.getValue("mesh_inputs"),
            )), meshes.resources)
            val measured = RigBuilder.rigContext(owner.analysis, config, owner.meshCache)
            val face = measured.faceRig
            val legacy = LegacyFaceInputs.capture(captured, face.centerY, face.radiusX, face.radiusY, face.eyeLineY,
                config.rigTuning.legacyFaceTuning(), config.headTurnStrength,
                StandardParameters.all.filter { it.id in parameters }.map { it.name }, tr("model.group.face"))
            val frames = ScaffoldFramesContext.build(structure.frames)
            val branches = ScaffoldBranchesContext.build(structure.branches, frames)
            val headFrame = ScaffoldFramesContext.decode(structure.frames).head
            val frameById = ScaffoldHead.build(headFrame, { it }).frames + branches.parents.values.associate { it.id to it.frame }
            val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
            val available = captured.values.getValue("mesh_inputs").jsonObject.getValue("materials").jsonArray.mapTo(hashSetOf()) {
                it.jsonObject.getValue("source_id").jsonPrimitive.content
            }
            val layers = canonicalByLayer.filterKeys { it in available }.entries.associate { (layer, id) -> id to byLayer.getValue(layer) }
            val parents = layers.mapValues { (_, layer) -> branches.parents.getValue(layer.source.id.raw).id }
            val turn = RigDepthTurnSource.capture(measured, config, layers, parents, frameById,
                meshes.resources.getValue("source_meshes"), layers.keys.associateWith { it }).encode()
            val phase = ModifierAlgorithmContext(buildJsonObject {
                put("base", legacy.values)
                put("turn", turn.values)
                if (body != null) {
                    val bodyInputs = LegacyBodyInputs.capture(captured, structure.torso, config.rigTuning.bodyMotion(),
                        emptyMap(), emptyMap()).values
                    put("body", JsonObject((bodyInputs - setOf("schema", "scaffold", "parameter_names", "group_names")) +
                        ("strength" to JsonPrimitive(config.bodyStrength))))
                }
            }, turn.resources)
            val eyes = structure.eyes?.let {
                RigFittedEyeSource.capture(instance, scaffold, owner.analysis, config, captured, it, owner.layersByMesh, owner.primaryByLayer)
            }
            return phase to eyes
        }
        val (before) = phase(native)
        val (after, capturedEyes) = phase(fitted)
        val eye = requireNotNull(capturedEyes)
        return ModifierAlgorithmContext(JsonObject(eye.values + ("fitted_head" to buildJsonObject {
            put("provider", head.id)
            body?.let { put("body_provider", it.id) }
            put("before", before.values)
            put("after", after.values)
        })), eye.resources + before.resources + after.resources, eye.upstream)
    }
}
