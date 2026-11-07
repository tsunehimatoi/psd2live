package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthLegacyInputs
import io.github.psd2live.algorithms.binding.MouthOutlineV2Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** Opt-in for the legacy mouth with a static scaffold parent. */
internal object RigMouthOutlineDefinition {
    private const val TYPE = MouthOutlineV2Algorithm.TYPE

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source)
        val plugin = object : ReadingModifierAlgorithmPlugin by MouthOutlineV2Algorithm(), ModifierVersionSelection {
            override val canonicalOptInType = TYPE
        }
        return legacy.copy(version = MouthOutlineV2Algorithm.VERSION, requires = setOf("rig.scaffold", "mouth/legacy"),
            parameters = { emptyList() }, upstreamInputs = true, plugin = plugin,
            changeWrites = setOf("mesh", "part", "hierarchy", "textures", "authoring"),
            composableKinds = setOf("mesh", "part", "hierarchy"),
            evaluate = { _, _, _ -> error("Mouth outline v2 requires captured legacy mouth inputs") },
            authorContext = { requireNotNull(source).captureOutline(it) })
    }

    private fun RigModifierSource.captureOutline(instance: RigModifierInstance): ModifierAlgorithmContext {
        val structure = provider(instance)
        require(structure.version == 2) { "Mouth outline v2 requires shared scaffold v2" }
        val active = activeBefore(instance)
        val mouth = active.singleOrNull { it.type == "mouth/legacy" && it.config["scaffold"]?.jsonPrimitive?.content == structure.id &&
            instance.targets.all(it.targets::contains) }
        require(mouth?.version == 2) { "Mouth outline v2 requires legacy mouth v2 on the same scaffold" }
        require(active.none { it.id != structure.id && it.id != mouth.id }) {
            "Mouth outline v2 requires a static scaffold and legacy mouth without additional bindings"
        }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(structure.config["corrections"])
        val config = sourceConfig().copy(recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            rigTuning = RigLegacyBindings.tuning(mouth, RigTuning()), generateDeformers = false,
            featureDisplacementEnabled = structure.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        require(!config.featureDisplacementEnabled) { "Mouth outline v2 requires an unwarped source frame" }
        val previous = MouthLegacyInputs.decode(legacyMouthContext(mouth))
        val frames = ScaffoldFramesContext.build(previous.scaffold.values.getValue("frames").jsonObject)
        val parents = ScaffoldBranchesContext.build(previous.scaffold.values.getValue("branches").jsonObject, frames).parents
        val owners = instance.targets.mapNotNull { target ->
            val layer = layersByMesh.getValue(target.id)
            val capture = previous.layers[target.id] ?: return@mapNotNull null
            val parent = parents[layer] ?: return@mapNotNull null
            target.id to RigMouthOutlineSource.Owner(layer, capture.canonical, retainedFrame = parent.frame)
        }.toMap()
        val outline = RigMouthOutlineSource.capture(analysis, config, owners, cache = meshCache)
        require(outline.targets.isNotEmpty()) { "Mouth outline v2 requires a readable whole mouth" }
        val raw = ScaffoldMeshContext.decodeRawMeshes(previous.scaffold.resources.getValue("source_meshes"))
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val neutral = outline.targets.mapValues { (id, target) ->
            val layerId = layersByMesh.getValue(id)
            val layer = layers.getValue(layerId)
            val capture = previous.layers.getValue(id)
            val parent = parents.getValue(layerId)
            val texture = LayerTexture.packed(layer, atlas.placementByLayerId.getValue(layerId), atlas)
            val placement = requireNotNull(texture.placement)
            MouthOutlineV2Algorithm.Owner(capture.canonical, ScaffoldBinding.deformerId(structure, id, parent.id.raw),
                target.frame, capture.frame.observation(), capture.aperture.observation(), MouthOutlineV2Algorithm.Texture(
                    placement.pageIndex, placement.positionX, placement.positionY, placement.scaleX, placement.scaleY,
                    placement.rotationDegrees, texture.pageWidth, texture.pageHeight, texture.offsetOriginX,
                    texture.offsetOriginY, texture.space.scaleX, texture.space.scaleY))
        }
        val materials = neutral.values.distinctBy { it.canonical }.map { owner ->
            ScaffoldMeshContext.Material(owner.canonical, layersByMesh.getValue(owner.canonical),
                raw.getValue(owner.canonical), ScaffoldMesh.Geometry.Zero)
        }
        return MouthOutlineV2Algorithm.capture(outline.encode(), neutral,
            ScaffoldMeshContext.capture(materials).resources.getValue("source_meshes"))
    }

    private fun Bounds.observation() = ModifierSourceBounds(left, top, right, bottom)
}
