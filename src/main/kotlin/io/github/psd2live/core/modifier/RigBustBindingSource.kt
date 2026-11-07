package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BustBindingInputs
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.PuppetModel

/** Captures source geometry and measurements before any body, face or chest motion is generated. */
internal object RigBustBindingSource {
    private val slots = setOf("PhysicsBustY", "PhysicsBustX")

    fun capture(analysis: PipelineAnalysis, atlas: PackedAtlas, config: PipelineConfig, meshCache: PreviewMeshCache,
                primaryByLayer: Map<String, String>, layersByMesh: Map<String, String>, instance: RigModifierInstance): BustBindingInputs {
        val applicable = RigModules.BODY_BUST.applies(RecipeSubject.of(analysis))
        val eligible = analysis.layers.filter { it.opaquePixels > 0 && it.source.id.raw in atlas.placementByLayerId &&
            it.semantic.tag in setOf(SemanticTag.TOPWEAR, SemanticTag.NECKWEAR) &&
            it.semantic.role !in setOf(PartRole.SLEEVE, PartRole.SKIRT, PartRole.RIBBON, PartRole.PENDANT) }
        // The original parameter kind depends on whether any source layer has chest motion, even
        // when this instance targets only a subset of the source layers.
        val canonicalByLayer = eligible.associate { layer ->
            val sourceId = layer.source.id.raw
            sourceId to (primaryByLayer[sourceId] ?: sourceId)
        }
        val layers = eligible.associate { layer ->
            canonicalByLayer.getValue(layer.source.id.raw) to BustBindingInputs.Layer(layer.source.id.raw,
                layer.semantic.role == PartRole.RIGID, PuppetSourceAtlas.tileIdFor(layer.source.id.raw).raw)
        }
        val targets = instance.targets.mapNotNull { target ->
            canonicalByLayer[layersByMesh[target.id]]?.let { target.id to it }
        }.toMap()
        val structure = RigBuilder.scaffoldSourceInputs(analysis, config, meshCache)
        val torso = structure.torso
        val geometry = RigBuilder.scaffoldMeshInputs(analysis, atlas, config, meshCache, canonicalByLayer)
        val (tiles, artwork) = PuppetSourceAtlas.build(analysis, atlas.placementByLayerId.filterKeys(canonicalByLayer::containsKey))
        val texture = PuppetIr.toIr(PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null,
            atlas = tiles.copy(pages = atlas.pages.map { AtlasPage(it.image.width, it.image.height) }), sources = artwork))
        val parameterNames = RigModules.BODY_BUST.defines.map { tr(it.nameKey) }
        val physics = PhysicsGenerator.moduleRules(setOf("ParamBustY", "ParamBustX", "ParamBodyAngleY", "ParamAngleY",
            "ParamBreath", "ParamBodyAngleX", "ParamBodyAngleZ")).filter { it.id in slots }.map(Physics3Json::group)
        val overrides = if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) emptyList() else
            instance.config["physics"]?.jsonObject.orEmpty().map { (id, value) ->
                require(id in slots) { "${instance.id}: unknown chest physics group: $id" }
                Physics3Json.group(RigPhysicsEdit.fromJson(value.jsonObject).copy(id = id))
            }
        return BustBindingInputs(BustBindingInputs.Torso(torso.centerX, torso.shoulderY, torso.waistY, torso.halfWidth),
            layers, targets, structure.frames, structure.branches, geometry.resources.getValue("source_meshes"),
            BustBindingInputs.TextureSpace(texture.textures, texture.authoring),
            BustBindingInputs.Names(parameterNames[0], parameterNames[1], tr("model.group.physics")), physics, overrides, applicable)
    }

    fun validatePhysics(rig: io.github.psd2live.format.model.RigIR, instance: RigModifierInstance, parameters: List<String>) {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return
        val available = rig.parameters.mapTo(HashSet()) { it.id }.apply { addAll(parameters) }
        for ((id, value) in instance.config["physics"]?.jsonObject.orEmpty()) {
            val setting = RigPhysicsEdit.fromJson(value.jsonObject)
            val issue = PhysicsCatalog.issueOf(setting, available)
            require(issue == null) { "${instance.id}: invalid physics $id: $issue" }
        }
    }
}
