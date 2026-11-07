package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

/** Captures the source-only transition for the fitted mouth and its shared head field. */
internal object RigFittedMouthModernSource {
    fun capture(owner: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = owner.provider(instance)
        require(scaffold.version == 2 && instance.targets.toSet() == scaffold.targets.toSet())
        val prefix = owner.activeBefore(instance).filter { it.id != scaffold.id && it.targets.any(instance.targets::contains) }
        require(prefix.all { it.config["scaffold"] == JsonPrimitive(scaffold.id) &&
            it.targets.toSet() == instance.targets.toSet() && it.type in setOf("body/legacy", "face/fitted", "eye/fitted", "brow/fitted") }) {
            "Fitted mouth v3 requires compatible body, face, eye and brow bindings on a shared scaffold"
        }
        val head = prefix.single { it.type == "face/fitted" }
        val body = prefix.singleOrNull { it.type == "body/legacy" }
        val (before, old) = RigFittedMouthModuleSource.context(owner, scaffold, prefix, null)
        val (after, new) = RigFittedMouthModuleSource.context(owner, scaffold, prefix + instance.copy(type = "mouth/fitted"), null)
        val byId = new.analysis.layers.associateBy { it.source.id.raw }
        val layers = instance.targets.associate { it.id to byId.getValue(owner.layersByMesh.getValue(it.id)) }
        val local = layers.filterValues { it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS ||
            it.semantic.tag in setOf(SemanticTag.FACE, SemanticTag.NOSE, SemanticTag.FACE_DETAIL) }
        val styled = layers.filterValues { layer ->
            layer.semantic.tag in setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.EARWEAR) ||
                layer.semantic.tag == SemanticTag.HEADWEAR &&
                layer.semantic.role !in setOf(PartRole.RIBBON, PartRole.PENDANT, PartRole.ANIMAL)
        }
        val fitted = new.fittedMouth(after)
        require(fitted?.source != null && local.isNotEmpty()) { "Fitted mouth v3 requires source mouth measurements" }
        val newFrames = local.mapValues { new.parentAndFrame(it.value, after) }
        val parents = newFrames.mapValues { (_, frame) -> frame.first?.raw }
        val measurements = RigFittedMouthSource.capture(new, after, parents, local,
            newFrames.mapValues { it.value.second }, local.keys.toList(), head.id).encode()
        fun phase(settings: PipelineConfig, measured: RigBuilder.RigContext): JsonObject {
            val structure = RigBuilder.scaffoldSourceInputs(owner.analysis, settings, owner.meshCache)
            val neutral = ModifierAlgorithmContext(buildJsonObject {
                put("schema", "scaffold/5")
                put("frames", structure.frames)
            })
            val face = measured.faceRig
            val angleParameters = RigLegacyBindings.spec(head.type).parameters
            val base = LegacyFaceInputs.capture(neutral, face.centerY, face.radiusX, face.radiusY, face.eyeLineY,
                settings.rigTuning.legacyFaceTuning(), settings.headTurnStrength,
                StandardParameters.all.filter { it.id in angleParameters }.map { it.name }, tr("model.group.face"))
            val layerFrames = local.mapValues { measured.parentAndFrame(it.value, settings) }
            val layerParents = layerFrames.mapValues { it.value.first }
            val frames = measured.deformers.mapNotNull { deformer ->
                deformer.parent?.let { id -> measured.resolveFrame(id, settings)?.let { id to it } }
            }.toMap() + layerFrames.values.mapNotNull { (id, frame) -> id?.let { it to frame } }.toMap()
            val turn = RigDepthTurnSource.capture(measured, settings, local, layerParents, frames)
                .copy(layers = emptyMap(), mouth = null).encode()
            return buildJsonObject {
                put("base", base.values)
                put("turn", turn.values)
                if (body != null) {
                    val captured = LegacyBodyInputs.capture(neutral, structure.torso, settings.rigTuning.bodyMotion(),
                        emptyMap(), emptyMap()).values
                    put("body", JsonObject(captured.filterKeys { it == "torso" || it == "tuning" } +
                        ("strength" to JsonPrimitive(settings.bodyStrength))))
                }
            }
        }
        fun meshes(settings: PipelineConfig, measured: RigBuilder.RigContext) = ScaffoldMeshContext.capture((local + styled).map { (id, layer) ->
            val data = RigBuilder.fittedMouthSourceMesh(layer, measured, settings, owner.atlas, owner.meshCache)
            ScaffoldMeshContext.Material(id, layer.source.id.raw,
                ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices), ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        fun frame(measured: RigBuilder.RigContext, settings: PipelineConfig, layer: ClassifiedLayer) = buildJsonObject {
            val (parent, bounds) = measured.parentAndFrame(layer, settings)
            put("parent", parent?.raw?.let(::JsonPrimitive) ?: JsonNull)
            put("bounds", JsonArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map(::JsonPrimitive)))
        }
        return ModifierAlgorithmContext(buildJsonObject {
            put("head", head.id)
            put("providers", JsonObject(prefix.associate { it.type to JsonPrimitive(it.id) }))
            put("before", phase(before, old))
            put("after", phase(after, new))
            put("mouth", measurements.values)
            put("parents", JsonObject(parents.mapValues { (_, id) -> id?.let(::JsonPrimitive) ?: JsonNull }))
            put("beforeFrames", JsonObject(local.mapValues { (_, layer) -> frame(old, before, layer) }))
            putJsonObject("styled") {
                styled.forEach { (id, layer) -> putJsonObject(id) {
                    put("tag", layer.semantic.tag.name)
                    put("before", frame(old, before, layer))
                    put("after", frame(new, after, layer))
                } }
            }
            put("formName", tr("model.parameter.mouthForm"))
            put("openName", tr("model.parameter.mouthOpen"))
            put("groupName", tr("model.group.mouth"))
        }, measurements.resources + mapOf("rawBefore" to meshes(before, old), "raw" to meshes(after, new)))
    }
}
