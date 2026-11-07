package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.*
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigClassicMouthOutlineDefinition {
    private const val TYPE = MouthOutlineV2Algorithm.TYPE
    private val plugin = object : ReadingModifierAlgorithmPlugin by MouthOutlineV4Algorithm(),
        ModifierVersionSelection, ModifierOptionalUpstream {
        override val canonicalOptInType = TYPE
        override val optionalUpstream = setOf("body/legacy")
    }

    fun definition(source: RigModifierSource?) = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source).copy(
        version = MouthOutlineV4Algorithm.VERSION,
        requires = setOf("rig.scaffold", "mouth/legacy", "face/legacy"),
        parameters = { emptyList() }, upstreamInputs = true, plugin = plugin,
        changeWrites = setOf("mesh", "deformer", "part", "hierarchy", "textures", "authoring"),
        composableKinds = setOf("mesh", "deformer", "part", "hierarchy"),
        authorContext = { requireNotNull(source).capture(it) },
    )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2 && instance.targets.toSet() == scaffold.targets.toSet()) {
            "Classic outline requires all targets of a shared v2 scaffold"
        }
        val prefix = activeBefore(instance).filter { it.id != scaffold.id && it.targets.any(instance.targets::contains) }
            .filterNot { RigLegacyBindings.find(it.type)?.let { spec -> spec.expressionToggles || spec.seeThrough } == true }
        require(prefix.all { it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.toSet() == instance.targets.toSet() &&
            it.type in setOf("body/legacy", "face/legacy", "eye/legacy", "brow/legacy", "mouth/legacy", "hair/legacy") }) {
            "Classic outline requires legacy bindings on the same scaffold"
        }
        val mouth = prefix.single { it.type == "mouth/legacy" && it.version == 2 }
        val head = prefix.single { it.type == "face/legacy" && it.version == 2 }
        val body = prefix.singleOrNull { it.type == "body/legacy" }
        require(body == null || body.version in 1..2) { "Classic outline requires a compatible legacy body" }
        val tuning = RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            prefix.singleOrNull { it.type == spec.type }?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val base = sourceConfig().copy(recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            rigTuning = tuning, generateDeformers = false, mouthOutlineEnabled = false,
            bodyStrength = body?.let(RigLegacyBindings::strength) ?: 1f, headTurnStrength = RigLegacyBindings.strength(head),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        require(!base.featureDisplacementEnabled) { "Classic outline requires an unwarped source frame" }
        val next = base.copy(mouthOutlineEnabled = true)
        val original = MouthLegacyInputs.decode(legacyMouthContext(mouth))
        val oldStructure = RigBuilder.scaffoldSourceInputs(analysis, base, meshCache)
        val newStructure = RigBuilder.scaffoldSourceInputs(analysis, next, meshCache)
        fun parents(structure: RigBuilder.ScaffoldSourceInputs) =
            ScaffoldBranchesContext.build(structure.branches, ScaffoldFramesContext.build(structure.frames)).parents
        val oldParents = parents(oldStructure)
        val newParents = parents(newStructure)
        val owners = instance.targets.mapNotNull { target ->
            val layer = layersByMesh.getValue(target.id)
            val measured = original.layers[target.id] ?: return@mapNotNull null
            val after = newParents[layer] ?: return@mapNotNull null
            target.id to RigMouthOutlineSource.Owner(layer, measured.canonical, retainedFrame = after.frame)
        }.toMap()
        val outline = RigMouthOutlineSource.capture(analysis, next, owners, cache = meshCache)
        require(outline.targets.isNotEmpty()) { "Classic outline requires a readable whole mouth" }
        val byLayer = analysis.layers.associateBy { it.source.id.raw }
        val selected = outline.targets.mapValues { (id, _) ->
            val layerId = layersByMesh.getValue(id)
            val layer = byLayer.getValue(layerId)
            val old = oldParents.getValue(layerId)
            require(old.id == newParents.getValue(layerId).id) { "Classic outline cannot move a source mesh to a different parent" }
            val captured = original.layers.getValue(id)
            val texture = LayerTexture.packed(layer, atlas.placementByLayerId.getValue(layerId), atlas)
            val placement = requireNotNull(texture.placement)
            MouthOutlineV2Algorithm.Owner(captured.canonical, ScaffoldBinding.deformerId(scaffold, id, old.id.raw),
                old.frame.observed(), captured.frame.observed(), captured.aperture.observed(), MouthOutlineV2Algorithm.Texture(
                    placement.pageIndex, placement.positionX, placement.positionY, placement.scaleX, placement.scaleY,
                    placement.rotationDegrees, texture.pageWidth, texture.pageHeight, texture.offsetOriginX,
                    texture.offsetOriginY, texture.space.scaleX, texture.space.scaleY))
        }
        val stationary = instance.targets.mapNotNull { (kind, id) ->
            if (kind != "mesh" || id in selected) return@mapNotNull null
            val layer = layersByMesh.getValue(id)
            val old = oldParents[layer] ?: return@mapNotNull null
            val after = newParents[layer] ?: return@mapNotNull null
            if (old == after) return@mapNotNull null
            require(old.id == after.id) { "Classic outline cannot move a source mesh to a different parent" }
            id to MouthOutlineV4Algorithm.Stationary(primaryByLayer.getValue(layer),
                ScaffoldBinding.deformerId(scaffold, id, old.id.raw), old.frame.observed(), after.frame.observed())
        }.toMap()
        val raw = ScaffoldMeshContext.decodeRawMeshes(original.scaffold.resources.getValue("source_meshes"))
        val materials = (selected.values.map { it.canonical } + stationary.values.map { it.canonical }).distinct().map { id ->
            ScaffoldMeshContext.Material(id, layersByMesh.getValue(id), raw.getValue(id), ScaffoldMesh.Geometry.Zero)
        }
        val meshes = ScaffoldMeshContext.capture(materials).resources.getValue("source_meshes")
        fun phase(settings: PipelineConfig, structure: RigBuilder.ScaffoldSourceInputs) = buildJsonObject {
            put("branches", structure.branches)
            val face = RigBuilder.rigContext(analysis, settings, meshCache).faceRig
            val angles = StandardParameters.all.filter { it.id in RigLegacyBindings.spec(head.type).parameters }
            val frameContext = ModifierAlgorithmContext(buildJsonObject {
                put("schema", "scaffold/5")
                put("frames", structure.frames)
            })
            put("face", LegacyFaceInputs.capture(frameContext, face.centerY, face.radiusX, face.radiusY,
                face.eyeLineY, settings.rigTuning.legacyFaceTuning(), settings.headTurnStrength, angles.map { it.name },
                tr("model.group.face")).values)
            if (body != null) {
                val observed = LegacyBodyInputs.capture(frameContext, structure.torso, settings.rigTuning.bodyMotion(),
                    emptyMap(), emptyMap()).values
                put("body", JsonObject(observed.filterKeys { it == "torso" || it == "tuning" } +
                    ("strength" to JsonPrimitive(settings.bodyStrength))))
            }
        }
        val captured = outline.encode()
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", "classic-outline/1")
            put("outline", captured.values)
            put("raw", meshes.digest)
            put("owners", Json.encodeToJsonElement(selected))
            put("stationary", Json.encodeToJsonElement(stationary))
            put("before", phase(base, oldStructure))
            put("after", phase(next, newStructure))
        }, captured.resources + (meshes.digest to meshes))
    }

    private fun Bounds.observed() = ModifierSourceBounds(left, top, right, bottom)
}
