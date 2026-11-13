package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.umamo.runtime.model.Drawable
import io.github.psd2live.algorithms.binding.GloomBindingAlgorithm
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun RigModifierSource.captureSkinLowering(targets: List<ModifierTarget>, scaffold: RigModifierInstance,
                                          previous: List<RigModifierInstance>, scaffoldMeshes: Map<String, Drawable>,
                                          base: ModifierAlgorithmContext, includeBlush: Boolean = false): ModifierAlgorithmContext {
    require(scaffold.version == 2) { "Skin lowering requires a shared scaffold" }
    val sourceLayers = analysis.layers.associateBy { it.source.id.raw }
    val contexts = HashMap<List<RigModifierInstance>, Pair<io.github.psd2live.core.PipelineConfig, RigBuilder.RigContext>>()
    val source = ArrayList<io.github.psd2live.format.model.Mesh>()
    val measurements = buildJsonObject {
        for (target in targets) {
            val layer = sourceLayers.getValue(layersByMesh.getValue(target.id))
            if (layer.semantic.tag !in setOf(SemanticTag.FACE, SemanticTag.NOSE) &&
                !(includeBlush && layer.semantic.tag == SemanticTag.FACE_DETAIL)) continue
            val preceding = previous.filter {
                target in it.targets && RigLegacyBindings.find(it.type)?.let { spec -> !spec.expressionToggles && !spec.seeThrough } == true
            }
            require(preceding.none { it.type == GloomBindingAlgorithm.TYPE }) { "Skin lowering requires an unshaded skin binding" }
            // The fitted face measures face details (blush) without the outline, and a source outline leaves them alone,
            // so they keep that turn. The face itself, and every skin under the legacy outline, still follow the
            // native outline.
            val fittedFace = preceding.any { it.type == RigLegacyBindings.FITTED_FACE }
            val detail = layer.semantic.tag == SemanticTag.FACE_DETAIL
            val framing = preceding.filterNot { detail && fittedFace && it.type == RigModules.MOUTH_OUTLINE.id && it.version > 1 }
            val (native, rig) = contexts.getOrPut(framing) {
                RigFittedMouthModuleSource.context(this@captureSkinLowering, scaffold, framing, null)
            }
            val alignedLayer = rig.analysis.layers.single { it.source.id == layer.source.id }
            val skin = RigBuilder.skinLoweringSource(alignedLayer, rig, native, atlas, meshCache, scaffoldMeshes.getValue(target.id))
            val parent = requireNotNull(skin.parentDeformerId) { "Skin lowering requires a bound skin parent" }
            source += PuppetIr.meshToIr(skin)
            putJsonObject(target.id) {
                put("providers", JsonArray(preceding.map { JsonPrimitive(it.id) }))
                put("parents", JsonArray((listOf(scaffold) + preceding).map {
                    JsonPrimitive(ScaffoldBinding.deformerId(it, target.id, parent.raw, shared = true))
                }.distinct()))
            }
        }
    }
    return ModifierAlgorithmContext(JsonObject(base.values + mapOf(
        "skin" to measurements,
        "turn_name" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")),
        "shape_name" to JsonPrimitive(tr("model.deformer.meshShape", "{meshName}")),
    )), base.resources + ("skin_forms" to ModifierAlgorithmResource(RigIrBinary.encode(
        RigIR(Canvas(1f, 1f), emptyList(), meshes = source)))))
}
