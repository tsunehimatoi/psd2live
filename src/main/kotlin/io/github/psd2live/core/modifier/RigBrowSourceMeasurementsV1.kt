package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.algorithms.binding.Side
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerBlend

/** Shared numerical capture. The caller supplies the real source snapshot and its actual prefix.
 * Native contexts may still build discarded motion; no generated motion is serialized here.
 */
internal object RigBrowSourceMeasurementsV1 {
    fun capture(
        source: RigModifierSource, snapshot: RigModifierInstance, scaffold: RigModifierInstance,
        prefix: List<RigModifierInstance>, eyeId: String, faceId: String,
    ): ModifierAlgorithmContext = with(source) {
        val original = sourceConfig()
        require(!skeletonEnabled && !hasCustomSourceStructure && analysis.source.groups.isEmpty() && analysis.source.layers.all { it.groupPath.isEmpty() }) {
            "Source brow measurement v1 requires flat original source artwork without skeletons, custom parents, warps or splits"
        }
        val leftTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE, SemanticTag.EYEBROW)
        val ordinaryTags = setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR, SemanticTag.FACE,
            SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.EYEBROW, SemanticTag.NOSE)
        require(analysis.layers.all { layer ->
            layer.opaquePixels > 0 && layer.source.id.raw in atlas.placementByLayerId &&
                layer.semantic.type == LayerType.PRESET && layer.semantic.parameter.isBlank() &&
                layer.source.blend == LayerBlend.Normal && !layer.source.clipped &&
                (original.layerOverrides[layer.source.id.raw]?.type ?: LayerType.PRESET) == LayerType.PRESET &&
                original.layerOverrides[layer.source.id.raw]?.parameter.orEmpty().isBlank() &&
                layer.semantic.tag in leftTags + ordinaryTags &&
                (layer.semantic.tag !in leftTags || layer.semantic.side == io.github.psd2live.core.Side.LEFT) &&
                (layer.semantic.tag !in ordinaryTags || layer.semantic.role == null)
        }) { "Source brow measurement v1 requires ordinary left-sided source materials without mouth interiors" }
        for (tag in listOf(SemanticTag.FACE, SemanticTag.EYEBROW, SemanticTag.EYEWHITE, SemanticTag.EYELASH))
            require(analysis.layers.count { it.semantic.tag == tag } == 1)
        val mouths = analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
        require(mouths.size == 1 && mouths.single().semantic.side == io.github.psd2live.core.Side.NONE)
        val targets = snapshot.targets.associate { it.id to layersByMesh.getValue(it.id) }
        require(targets.values.toSet() == analysis.source.layers.mapTo(hashSetOf()) { it.id.raw } &&
            targets.size == analysis.layers.size && targets.values.toSet().size == targets.size &&
            targets.all { (id, layer) -> primaryByLayer[layer] == id } && layersByMesh.size == targets.size) {
            "Source brow measurement v1 requires the complete original unsplit and uncopied target inventory"
        }
        // Only the caller-selected real predecessor snapshot enters the measurement configuration.
        val (native, measured) = RigFittedMouthModuleSource.context(this, scaffold, prefix, null)
        require(measured.faceRig.initialAngleZ == 0f && measured.faceTurn(native) == null &&
            measured.fittedEyes(native)?.rigs?.keys == setOf(io.github.psd2live.core.Side.LEFT)) {
            "Source brow measurement v1 requires an upright single-left-eye fallback"
        }
        val measurements = RigBuilder.browShapeSourceInputs(analysis, atlas, native, meshCache,
            snapshot.targets.associate { it.id to layersByMesh.getValue(it.id) }, eyeId) { _, parent -> parent }
        require(measurements.targets.size == 1 && measurements.targets.values.single().fit.side == Side.LEFT &&
            measurements.targets.values.single().eye != null) { "Source brow measurement v1 requires one paired source brow" }
        val layers = measured.analysis.layers.associateBy { it.source.id.raw }
        val source = measurements.encode()
        val structure = RigBuilder.scaffoldSourceInputs(analysis, native, meshCache)
        val frameContext = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "scaffold/5"); put("frames", structure.frames); put("branches", structure.branches)
        })
        val f = measured.faceRig
        val legacyFace = LegacyFaceInputs.capture(frameContext, f.centerY, f.radiusX, f.radiusY, f.eyeLineY,
            native.rigTuning.legacyFaceTuning(), native.headTurnStrength, StandardParameters.all.take(3).map { it.name }, tr("model.group.face"))
        val body = LegacyBodyInputs.capture(frameContext, structure.torso, native.rigTuning.bodyMotion(), emptyMap(), emptyMap()).values
        val shell = RigBuilder.headTurnMeasurements(f, native)
        val fallback = FittedHeadTurnSource(shell[0], shell[1], shell[2], shell[3], shell[6], shell[4], shell[5],
            measured.faceLandmarks(native), RigModules.turnStyle(native))
        require(fallback.styledTurn() == null)
        ModifierAlgorithmContext(buildJsonObject {
            put("brow", source.values)
            putJsonObject("source_ancestry") {
                put("face", legacyFace.values)
                put("body", JsonObject(body.filterKeys { it in setOf("torso", "tuning") } + ("strength" to JsonPrimitive(native.bodyStrength))))
                put("fallback", fallback.encode())
                putJsonArray("layers") {
                    for ((id, sourceId) in targets) {
                        val layer = layers.getValue(sourceId)
                        add(buildJsonObject {
                            put("id", id); put("source_id", sourceId); put("name", layer.source.name)
                            put("tag", layer.semantic.tag.name); put("side", layer.semantic.side.name)
                            put("role", layer.semantic.role?.name?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    }
                }
            }
            putJsonObject("head_turn") {
                put("provider", faceId)
                put("shell", JsonArray(RigBuilder.headTurnMeasurements(measured.faceRig, native).map(::JsonPrimitive)))
            }
            put("native_ids", JsonObject(measurements.targets.keys.associateWith { JsonPrimitive(primaryByLayer.getValue(layersByMesh.getValue(it))) }))
            put("mesh_names", JsonObject(measurements.targets.keys.associateWith { JsonPrimitive(layers.getValue(layersByMesh.getValue(it)).source.name) }))
            put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
            put("shape_name", tr("model.deformer.meshShape", "{meshName}"))
        }, source.resources)
    }
}
