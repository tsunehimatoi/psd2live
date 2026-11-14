package io.github.psd2live.core.modifier.source

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.BodyStance
import io.github.psd2live.algorithms.binding.BrowFittedInputs
import io.github.psd2live.algorithms.binding.BrowLegacyInputs
import io.github.psd2live.algorithms.binding.BrowShapeInputs
import io.github.psd2live.algorithms.binding.BrowSpringInputs
import io.github.psd2live.algorithms.binding.BustBindingInputs
import io.github.psd2live.algorithms.binding.ChainKind
import io.github.psd2live.algorithms.binding.CreatureAlgorithm
import io.github.psd2live.algorithms.binding.CreatureInputs
import io.github.psd2live.algorithms.binding.DepthTurnInputs
import io.github.psd2live.algorithms.binding.EyeFittedInputs
import io.github.psd2live.algorithms.binding.EyeHighlightInput
import io.github.psd2live.algorithms.binding.EyeLegacyInputs
import io.github.psd2live.algorithms.binding.EyeLegacyMotion
import io.github.psd2live.algorithms.binding.EyeSmileInputs
import io.github.psd2live.algorithms.binding.EyeSquashInputs
import io.github.psd2live.algorithms.binding.EyeWideInputs
import io.github.psd2live.algorithms.binding.HairChainsAlgorithm
import io.github.psd2live.algorithms.binding.HairChainsInputs
import io.github.psd2live.algorithms.binding.HairFollowMotion
import io.github.psd2live.algorithms.binding.HairLegacyInputs
import io.github.psd2live.algorithms.binding.HairLegacyMotion
import io.github.psd2live.algorithms.binding.LegacyBodyInputs
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.LidFadeInputs
import io.github.psd2live.algorithms.binding.NeckInputs
import io.github.psd2live.algorithms.binding.RibbonsAlgorithm
import io.github.psd2live.algorithms.binding.RibbonsInputs
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.algorithms.binding.SkirtAlgorithm
import io.github.psd2live.algorithms.binding.SkirtInputs
import io.github.psd2live.algorithms.binding.TorsoFrame
import io.github.psd2live.core.modifier.legacyFaceTuning
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.LayerGroup
import io.github.psd2live.core.LayerTexture
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.MeshResolution
import io.github.psd2live.core.MouthCurve
import io.github.psd2live.core.PackedAtlas
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.PreviewMeshCache
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.fit.HairKind
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.fit.EyeArtworkFitting
import io.github.psd2live.core.fit.EyeModules
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierSourceBounds
import io.github.psd2live.core.modifier.RigFittedEyeSource
import io.github.psd2live.core.modifier.RigSourceCapture
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.i18n.tr
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*

/**
 * Static host-side collector for modifier source inputs.
 * Captures source artwork, raw grid topology, coordinate frames, and alignment without running
 * deformer/binding stages or generating before/after transition snapshots.
 */
internal object RigStaticSourceCollector {
    internal data class ScaffoldSourceInputs(
        val frames: JsonObject,
        val branches: JsonObject,
        val torso: TorsoFrame,
        val eyes: RigFittedEyeSource.Measurements?,
    )

    fun scaffoldSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
    ): ScaffoldSourceInputs {
        require(config.rigEdits.skeleton?.enabled != true) {
            "Source scaffold measurements require a recipe without a skeleton"
        }
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val head = RigBuilder.scaffoldHeadInput(
            context.bodyFrame, context.head, context.face, context.faceRig,
            context.frontHair, context.backHair, config.featureDisplacementEnabled,
            !config.hairSimulationFront, !config.hairSimulationBack,
        )
        val frames = ScaffoldFramesContext.capture(
            context.bodyFrame, RigBuilder.scaffoldStanding(context.stance), head, { tr(it) },
        )
        val branches = ScaffoldBranchesContext.capture(
            context.analysis.layers.filter { it.opaquePixels > 0 }.map { layer ->
                ScaffoldBranchesContext.Layer(
                    layer.source.id.raw,
                    layer.source.name,
                    layer.semantic.tag.name,
                    layer.semantic.side,
                    RigBuilder.riggedLayerIn(layer, context.analysis.anchors, context.headSpace).bounds,
                    RigBuilder.scaffoldSlot(layer, context.analysis.anchors),
                )
            },
            context.bodyFrame,
            context.stance.legsFrame,
            head,
            { tr("model.deformer.pair", it) },
            { tr("model.deformer.armHang", it) },
        )
        val eyeMeasurements = if (config.recipe.base("eye") === RigModules.EYE_FITTED) {
            val eyes = context.analysis.layers.filter {
                it.semantic.tag in setOf(
                    SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE, SemanticTag.IRIDES,
                )
            }
            RigFittedEyeSource.Measurements(
                eyes,
                context.faceRig.centerX,
                context.headSpace,
                eyes.filter { RigBuilder.inferredGroup(it, context.analysis.anchors) == LayerGroup.HEAD }
                    .mapTo(hashSetOf()) { it.source.id.raw },
                eyes.associate { layer ->
                    val aligned = context.rigLayer(layer)
                    layer.source.id.raw to (aligned to RigBuilder.matchingEyeWhiteBounds(aligned, context.eyeWhiteLayers))
                },
            )
        } else null
        return ScaffoldSourceInputs(frames, branches, context.stance.torso, eyeMeasurements)
    }

    fun scaffoldFrameInputs(analysis: PipelineAnalysis, config: PipelineConfig, cache: PreviewMeshCache): JsonObject =
        scaffoldSourceInputs(analysis, config, cache).frames

    fun scaffoldMeshInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        canonicalByLayer: Map<String, String>,
    ): ModifierAlgorithmContext {
        require(!config.mouthOutlineEnabled && config.rigEdits.skeleton?.enabled != true)
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val tuning = config.rigTuning
        val curve = if (config.mouthShape == "custom") config.mouthCurve else MouthCurve.preset(config.mouthShape)
        val unit = Bounds(0f, 0f, 1f, 1f)
        val materials = context.analysis.layers.mapNotNull { layer ->
            val id = canonicalByLayer[layer.source.id.raw] ?: return@mapNotNull null
            val placement = atlas.placementByLayerId[layer.source.id.raw] ?: return@mapNotNull null
            if (layer.opaquePixels == 0) return@mapNotNull null
            val aligned = context.rigLayer(layer)
            val space = context.headSpace.takeIf {
                RigBuilder.inferredGroup(layer, context.analysis.anchors) == LayerGroup.HEAD
            }
            val source = RigBuilder.buildGridMesh(
                layer, unit, space, config, cache, MeshResolution.unitScale(config, context.analysis.source),
            )
            val raw = ScaffoldMesh.RawMesh(
                source.rigPositions,
                LayerTexture.packed(layer, placement, atlas).bind(source.mesh.uvs),
                source.mesh.indices,
            )
            val geometry = when (layer.semantic.tag) {
                SemanticTag.EYEWHITE, SemanticTag.EYELASH -> ScaffoldMesh.Geometry.Eye(
                    layer.semantic.tag == SemanticTag.EYELASH,
                    aligned.bounds,
                    RigBuilder.matchingEyeWhiteBounds(aligned, context.eyeWhiteLayers),
                    aligned.centroidY,
                    layer.source.bounds.left,
                    if (layer.semantic.tag == SemanticTag.EYELASH && context.faceRig.initialAngleZ == 0f) {
                        RigBuilder.eyelashCenterline(aligned)
                    } else null,
                    tuning.blinkCorner,
                    tuning.blinkDepth,
                    tuning.eyelashSquash,
                )
                SemanticTag.IRIDES -> ScaffoldMesh.Geometry.Iris(
                    aligned.centroidX, aligned.centroidY, tuning.irisJellySquash, tuning.irisJellyStretch,
                )
                SemanticTag.EYEBROW -> ScaffoldMesh.Geometry.Brow(aligned.bounds.height, tuning.browLift)
                SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN -> ScaffoldMesh.Geometry.Mouth(
                    RigBuilder.mouthApertureFor(aligned) ?: aligned.bounds,
                    curve.points.map { ScaffoldMesh.CurvePoint(it.x, it.y) },
                    tuning.mouthClosedNarrow,
                    tuning.mouthSeam,
                    tuning.mouthSmileBase,
                    tuning.mouthSmile,
                    tuning.mouthFormWiden,
                )
                else -> ScaffoldMesh.Geometry.Zero
            }
            ScaffoldMeshContext.Material(id, layer.source.id.raw, raw, geometry)
        }
        return ScaffoldMeshContext.capture(materials)
    }

    fun lidFadeSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val eyes = context.fittedEyesForSourceLidFade(config)?.rigs.orEmpty()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val targets = targetLayers.mapNotNull { (id, layerId) ->
            val layer = layers[layerId] ?: return@mapNotNull null
            val semantic = layer.semantic
            if (semantic.tag != SemanticTag.EYELASH || (semantic.role != PartRole.LID && semantic.role != PartRole.SHADOW)) {
                return@mapNotNull null
            }
            val override = config.layerOverrides[layerId]
            if ((override?.type ?: semantic.type) != LayerType.PRESET &&
                (override?.parameter ?: semantic.parameter).isNotBlank()
            ) return@mapNotNull null
            val eye = eyes[semantic.side] ?: return@mapNotNull null
            val parameter = EyeModules.open(semantic.side).raw
            id to LidFadeInputs(parameter, eye.crease, layer.source.opacity.coerceIn(0f, 1f))
        }.toMap()
        return LidFadeInputs.encode(targets)
    }

    fun eyeWideSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val artwork = context.analysis.layers.filter { it.semantic.tag in RigBuilder.eyeMeshTags && it.opaquePixels > 0 }
        val byLayer = artwork.associateBy { it.source.id.raw }
        val firstWhite = artwork.firstOrNull { it.semantic.tag == SemanticTag.EYEWHITE }
        val space = firstWhite?.let { context.headSpaceFor(it, config) }
        val alignment = space?.let {
            val radians = it.angleDegrees * kotlin.math.PI.toFloat() / 180f
            EyeFittedInputs.Alignment(it.pivotX, it.pivotY, kotlin.math.cos(radians), kotlin.math.sin(radians))
        }
        val moves = mapOf(Side.LEFT to "eye.L", Side.RIGHT to "eye.R").mapKeys { it.key.name }.mapValues { (_, feature) ->
            context.rigMoves(config, feature) { x, y -> space?.toAligned(x, y) ?: (x to y) }
        }
        val topology = LinkedHashMap<String, ScaffoldMeshContext.Material>()
        val targets = targetLayers.mapNotNull { (id, layerId) ->
            val layer = byLayer[layerId] ?: return@mapNotNull null
            val placement = atlas.placementByLayerId[layerId] ?: return@mapNotNull null
            val aligned = context.rigLayer(layer)
            val (parent, frame) = context.parentAndFrame(layer, config)
            topology.getOrPut(layerId) {
                val mesh = RigBuilder.buildGridMesh(
                    layer, frame, context.headSpaceFor(layer, config), config, cache,
                    MeshResolution.unitScale(config, context.analysis.source),
                )
                val raw = ScaffoldMesh.RawMesh(
                    mesh.rigPositions,
                    LayerTexture.packed(layer, placement, atlas).bind(mesh.mesh.uvs),
                    mesh.mesh.indices,
                )
                ScaffoldMeshContext.Material(layerId, layerId, raw, ScaffoldMesh.Geometry.Zero)
            }
            val override = config.layerOverrides[layerId]
            val preset = (override?.type ?: layer.semantic.type) == LayerType.PRESET
            val automaticOpacity = preset || (override?.parameter ?: layer.semantic.parameter).isBlank()
            id to EyeWideInputs.Target(
                layerId,
                parent?.raw?.let { parentId(id, it) },
                ModifierSourceBounds(frame.left, frame.top, frame.right, frame.bottom),
                aligned.centroidX,
                aligned.centroidY,
                preset,
                automaticOpacity,
                layer.source.opacity.coerceIn(0f, 1f),
            )
        }.toMap()
        val source = RigSourceCapture.capture(artwork, targets.mapValues { it.value.layer })
        val stamps = artwork.filter { it.semantic.tag == SemanticTag.EYELASH }
            .associate { it.source.id.raw to EyeArtworkFitting.stamp(it) }
        return EyeWideInputs(
            context.faceRig.centerX, alignment, stamps, moves, config.recipe.eyeParts, targets,
            config.rigTuning.irisJellySquash, config.rigTuning.irisJellyStretch,
        ).encode(source, ScaffoldMeshContext.capture(topology.values.toList()).resources.getValue("source_meshes"))
    }

    fun eyeSmileSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        parameterNames: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val artwork = context.analysis.layers.filter { it.semantic.tag in RigBuilder.eyeMeshTags && it.opaquePixels > 0 }
        val byLayer = artwork.associateBy { it.source.id.raw }
        val firstWhite = artwork.firstOrNull { it.semantic.tag == SemanticTag.EYEWHITE }
        val space = firstWhite?.let { context.headSpaceFor(it, config) }
        val alignment = space?.let {
            val radians = it.angleDegrees * kotlin.math.PI.toFloat() / 180f
            EyeFittedInputs.Alignment(it.pivotX, it.pivotY, kotlin.math.cos(radians), kotlin.math.sin(radians))
        }
        val moves = mapOf(Side.LEFT to "eye.L", Side.RIGHT to "eye.R").mapKeys { it.key.name }.mapValues { (_, feature) ->
            context.rigMoves(config, feature) { x, y -> space?.toAligned(x, y) ?: (x to y) }
        }
        val topology = LinkedHashMap<String, ScaffoldMeshContext.Material>()
        val targets = targetLayers.mapNotNull { (id, layerId) ->
            val layer = byLayer[layerId]?.takeIf { it.semantic.tag != SemanticTag.IRIDES } ?: return@mapNotNull null
            val placement = requireNotNull(atlas.placementByLayerId[layerId]) { "Missing fitted lid atlas placement: $layerId" }
            val (parent, frame) = context.parentAndFrame(layer, config)
            topology.getOrPut(layerId) {
                val mesh = RigBuilder.buildGridMesh(
                    layer, frame, context.headSpaceFor(layer, config), config, cache,
                    MeshResolution.unitScale(config, context.analysis.source),
                )
                val raw = ScaffoldMesh.RawMesh(
                    mesh.rigPositions,
                    LayerTexture.packed(layer, placement, atlas).bind(mesh.mesh.uvs),
                    mesh.mesh.indices,
                )
                ScaffoldMeshContext.Material(layerId, layerId, raw, ScaffoldMesh.Geometry.Zero)
            }
            id to EyeSmileInputs.Target(
                layerId,
                parent?.raw?.let { parentId(id, it) },
                ModifierSourceBounds(frame.left, frame.top, frame.right, frame.bottom),
            )
        }.toMap()
        val source = RigSourceCapture.capture(artwork, targets.mapValues { it.value.layer })
        val stamps = artwork.filter { it.semantic.tag == SemanticTag.EYELASH }
            .associate { it.source.id.raw to EyeArtworkFitting.stamp(it) }
        return EyeSmileInputs(
            context.faceRig.centerX, alignment, stamps, moves, config.recipe.eyeParts, targets,
            parameterNames, tr("model.group.eyes"),
        ).encode(source, ScaffoldMeshContext.capture(topology.values.toList()).resources.getValue("source_meshes"))
    }

    fun eyeSquashSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val eyes = context.fittedEyes(config)?.rigs.orEmpty()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val tuning = config.rigTuning
        val measurements = targetLayers.mapNotNull { (target, layerId) ->
            val layer = layers[layerId] ?: return@mapNotNull null
            if (layer.semantic.tag != SemanticTag.IRIDES || layer.opaquePixels == 0) return@mapNotNull null
            val placement = atlas.placementByLayerId[layerId] ?: return@mapNotNull null
            val aligned = context.rigLayer(layer)
            val eye = eyes[aligned.semantic.side] ?: return@mapNotNull null
            val (parent, frame) = context.parentAndFrame(layer, config)
            val source = RigBuilder.buildGridMesh(
                layer, frame, context.headSpaceFor(layer, config), config, cache,
                MeshResolution.unitScale(config, context.analysis.source),
            )
            val raw = ScaffoldMesh.RawMesh(
                source.rigPositions,
                LayerTexture.packed(layer, placement, atlas).bind(source.mesh.uvs),
                source.mesh.indices,
            )
            target to EyeSquashInputs(
                aligned.semantic.side,
                eye.crease,
                parent?.raw?.let { parentId(target, it) },
                frame,
                ScaffoldMesh.Geometry.Iris(aligned.centroidX, aligned.centroidY, tuning.irisJellySquash, tuning.irisJellyStretch),
                raw,
            )
        }.toMap()
        return EyeSquashInputs.encode(measurements)
    }

    fun browShapeSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        eyeProvider: String?,
        parentId: (String, String) -> String,
        frameConfig: PipelineConfig = config,
    ): BrowShapeInputs {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val frameContext = if (frameConfig == config) context else RigBuilder.rigContext(analysis, frameConfig, cache, neutralScaffold = true)
        val (fits, eyes) = context.fittedBrowMeasurements(config)
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val names = RigModules.BROW_SHAPE.takeIf {
            it.applies(RecipeSubject.of(context.analysis))
        }?.defines.orEmpty().map { tr(it.nameKey) }
        val targets = targetLayers.mapNotNull { (target, layerId) ->
            val layer = layers[layerId]?.takeIf {
                it.semantic.tag == SemanticTag.EYEBROW && it.semantic.role == null && it.opaquePixels > 0
            } ?: return@mapNotNull null
            val fit = fits[layer.semantic.side] ?: return@mapNotNull null
            val placement = atlas.placementByLayerId[layerId] ?: return@mapNotNull null
            val (parent, frame) = frameContext.parentAndFrame(layer, frameConfig)
            val source = RigBuilder.buildGridMesh(
                layer, frame, context.headSpaceFor(layer, config), config, cache,
                MeshResolution.unitScale(config, context.analysis.source),
            )
            val raw = ScaffoldMesh.RawMesh(
                source.rigPositions,
                LayerTexture.packed(layer, placement, atlas).bind(source.mesh.uvs),
                source.mesh.indices,
            )
            val eye = eyes?.rigs?.get(fit.side)?.let { BrowShapeInputs.Eye(it.opening, it.fit.width) }
            target to BrowShapeInputs.Target(fit, eye, parent?.let { parentId(target, it.raw) }, frame, raw)
        }.toMap()
        return BrowShapeInputs(
            names, tr("model.group.brows"), targets,
            eyeProvider.takeIf { targets.values.any { it.eye != null } },
        )
    }

    fun browSpringSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        kept: Set<ParameterId>,
        eyeProvider: String?,
        shapeProvider: String?,
        parentId: (String, String) -> String,
        partId: (String) -> String = { it },
        frameConfig: PipelineConfig = config,
    ): BrowSpringInputs {
        val effective = config.copy(featureDisplacementEnabled = false)
        val scaffoldConfig = effective.copy(recipe = io.github.psd2live.core.recipe.RigRecipe.Legacy.copy(
            moves = effective.recipe.moves,
            eyeParts = effective.recipe.eyeParts,
        ))
        val context = RigBuilder.rigContext(analysis, scaffoldConfig, cache)
        val frameEffective = frameConfig.copy(featureDisplacementEnabled = false)
        val frameContext = if (frameEffective == scaffoldConfig) context else RigBuilder.rigContext(analysis, frameEffective, cache)
        val (fits, eyes) = context.fittedBrowMeasurements(config)
        val module = RigModules.BROW_SPRING
        val names = if (!module.applies(RecipeSubject.of(context.analysis))) emptyMap() else {
            listOf(Side.LEFT, Side.RIGHT).zip(module.defines.map { tr(it.nameKey) }).toMap()
        }
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val ordered = RigBuilder.orderMouthLayers(context.analysis.layers.sortedBy { it.source.order })
        val step = RigBuilder.generatedOrderStep(config.drawOrderStep, ordered.size)
        val defaultOrders = ordered.mapIndexed { index, layer ->
            layer.source.id.raw to ((ordered.size - index) * step).toFloat()
        }.toMap()
        val meshConfig = effective.copy(recipe = io.github.psd2live.core.recipe.RigRecipe.Legacy.copy(
            modern = effective.recipe.modern,
            bases = io.github.psd2live.core.recipe.RigRecipe.Legacy.bases +
                ("brow" to effective.recipe.bases.getValue("brow")),
            moves = effective.recipe.moves,
            eyeParts = effective.recipe.eyeParts,
        ))
        val meshContext = if (meshConfig == effective) context else RigBuilder.rigContext(analysis, meshConfig, cache)
        val sourceParents = RigBuilder.faceTurnAndLean(context, effective, RigBuilder.stagesOf(cache)).associateBy { it.id }
        val parameters = RigBuilder.standardParameters(context, effective) + RigBuilder.moduleParameters(context, effective, emptyList())
        val targets = targetLayers.mapNotNull { (id, layerId) ->
            val layer = layers[layerId]?.takeIf {
                it.semantic.tag == SemanticTag.EYEBROW && it.semantic.role == null && it.opaquePixels > 0
            } ?: return@mapNotNull null
            val fit = fits[layer.semantic.side] ?: return@mapNotNull null
            val placement = atlas.placementByLayerId[layerId] ?: return@mapNotNull null
            val (parent, frame) = frameContext.parentAndFrame(layer, frameEffective)
            val data = RigBuilder.buildGridMesh(
                layer, frame, meshContext.headSpaceFor(layer, meshConfig), meshConfig, cache,
                MeshResolution.unitScale(meshConfig, meshContext.analysis.source),
            )
            val raw = ScaffoldMesh.RawMesh(
                data.rigPositions,
                LayerTexture.packed(layer, placement, atlas).bind(data.mesh.uvs),
                data.mesh.indices,
            )
            val nativeBranch = generateSequence(parent) { sourceParents.getValue(it).parent }.map(sourceParents::getValue).toList()
            val native = PuppetModel(parameters, emptyList(), nativeBranch, emptyList(), emptyList(), null)
            val projected = BindingProjection.retain(native, kept, mapOf(StandardParameters.MOUTH_OPEN to 1f))
            val branch = projected.deformers.map { deformer ->
                val ref = PuppetIr.deformerToIr(deformer)
                val mappedParent = ref.parent?.let { parentId(id, it) }
                val mappedId = parentId(id, ref.id)
                val mappedPart = ref.part?.let(partId)
                val d = when (ref) {
                    is Deformer.Warp -> ref.copy(id = mappedId, parent = mappedParent, part = mappedPart)
                    is Deformer.Rotation -> ref.copy(id = mappedId, parent = mappedParent, part = mappedPart)
                }
                BrowSpringInputs.fingerprint(d)
            }
            val eye = eyes?.rigs?.get(fit.side)?.let { BrowSpringInputs.Eye(it.opening, it.fit.width) }
            val order = config.drawOrderOverrides[layerId] ?: config.drawOrderOverrides[id] ?: defaultOrders.getValue(layerId)
            id to BrowSpringInputs.Target(fit, eye, frame, raw, branch, order.coerceIn(0f, 1000f), layer.source.opacity.coerceIn(0f, 1f))
        }.toMap()
        return BrowSpringInputs(
            names,
            listOf(Side.LEFT, Side.RIGHT).zip(listOf("L", "R").map { tr("model.physics.browSpring", it) }).toMap(),
            tr("model.group.physics"),
            targets,
            eyeProvider.takeIf { targets.values.any { it.eye != null } },
            shapeProvider,
        )
    }

    fun neckSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val face = context.faceRig
        val (px, py) = face.coordinateSpace.toCanvas(face.centerX, face.mouthLineY)
        val neck = context.analysis.layers.filter { it.semantic.tag == SemanticTag.NECK && it.opaquePixels > 0 }
        val bases = neck.filter { it.semantic.role == null }
        val masks = bases.sortedBy { it.source.order }.mapNotNull { layer ->
            primaryByLayer[layer.source.id.raw]?.takeIf { layer.source.id.raw in atlas.placementByLayerId }
        }
        val byLayer = neck.associateBy { it.source.id.raw }
        val materials = LinkedHashMap<String, ScaffoldMeshContext.Material>()
        val targets = LinkedHashMap<String, NeckInputs.Target>()
        val canApply = context.analysis.layers.any { it.semantic.tag == SemanticTag.FACE && it.opaquePixels > 0 }
        if (canApply) for ((target, layerId) in targetLayers) {
            val layer = byLayer[layerId] ?: continue
            val canonical = primaryByLayer.getValue(layerId)
            val placement = atlas.placementByLayerId[layer.source.id.raw] ?: continue
            val (parent, frame) = context.parentAndFrame(layer, config)
            materials.getOrPut(canonical) {
                val raw = RigBuilder.buildGridMesh(
                    layer, Bounds(0f, 0f, 1f, 1f), null, config, cache,
                    MeshResolution.unitScale(config, context.analysis.source),
                )
                ScaffoldMeshContext.Material(
                    canonical,
                    layerId,
                    ScaffoldMesh.RawMesh(
                        raw.rigPositions,
                        LayerTexture.packed(layer, placement, atlas).bind(raw.mesh.uvs),
                        raw.mesh.indices,
                    ),
                    ScaffoldMesh.Geometry.Zero,
                )
            }
            targets[target] = NeckInputs.Target(
                canonical,
                context.rigLayer(layer).bounds,
                frame,
                parent?.let { parentId(target, it.raw) },
                layer.semantic.role == PartRole.SHADOW,
            )
        }
        return NeckInputs(
            px, py, bases.map { it.bounds }.reduceOrNull(Bounds::union), masks, targets,
        ).encode(ScaffoldMeshContext.capture(materials.values.toList()))
    }

    private fun boundsList(bounds: Bounds): List<Float> = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)

    private fun chainPartAttachment(
        layer: ClassifiedLayer,
        context: RigBuilder.RigContext,
        skeletonEnabled: Boolean,
    ): Triple<String, String, Pair<List<Float>, String?>> {
        val (deformer, frame) = RigBuilder.defaultChainAttachment(layer, context, skeletonEnabled)
        val (part, accessoryName) = when {
            layer.semantic.tag == SemanticTag.FRONT_HAIR -> "PartFrontHair" to null
            layer.semantic.tag == SemanticTag.BACK_HAIR -> "PartBackHair" to null
            RigBuilder.inferredGroup(layer, context.analysis.anchors) == LayerGroup.HEAD ->
                "PartHeadAccessories" to tr("model.part.headAccessories")
            else -> "PartBody" to null
        }
        return Triple(deformer, part, boundsList(frame) to accessoryName)
    }

    private fun pairedClothIds(
        layers: List<ClassifiedLayer>,
        context: RigBuilder.RigContext,
        skeletonEnabled: Boolean,
    ): Set<String> {
        val candidates = layers.filter { layer ->
            layer.opaquePixels > 0 && (layer.semantic.side == Side.LEFT || layer.semantic.side == Side.RIGHT)
        }.map { layer ->
            val (deformer, part, _) = chainPartAttachment(layer, context, skeletonEnabled)
            val (_, frame) = RigBuilder.defaultChainAttachment(layer, context, skeletonEnabled)
            ScaffoldPairs.Layer(
                layer.source.id.raw, layer.source.name, layer.semantic.tag.name, layer.semantic.side,
                context.rigLayer(layer).bounds, DeformerId(deformer), frame, PartId(part),
            )
        }
        return ScaffoldPairs.build(candidates, emptySet(), { tr("model.deformer.pair", it) }).flatMapTo(HashSet()) { it.layerIds }
    }

    fun skirtSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        meshesByLayer: Map<String, List<String>>,
        targetIds: Set<String>,
        ribbonTargets: Set<String>,
        impulses: List<String>,
        rawContext: io.github.psd2live.core.modifier.ModifierAlgorithmContext,
    ): io.github.psd2live.core.modifier.ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val clothLayers = context.analysis.layers.filter { it.opaquePixels > 0 && HairKind.clothOf(it) != null }
        val paired = pairedClothIds(clothLayers, context, config.rigEdits.skeleton?.enabled == true)
        val layers = clothLayers.map { layer ->
            val kind = requireNotNull(HairKind.clothOf(layer))
            val meshes = if (kind == HairKind.SKIRT) meshesByLayer[layer.source.id.raw].orEmpty().filter { it in targetIds } else emptyList()
            SkirtInputs.Layer(
                id = layer.source.id.raw,
                tag = layer.semantic.tag.name,
                bounds = boundsList(layer.bounds),
                pixels = layer.opaquePixels,
                kind = kind.name,
                meshes = meshes,
                paired = layer.source.id.raw in paired,
                warpName = tr("model.deformer.clothChain", layer.source.name),
                parameterName = tr("model.parameter.hairChain", layer.source.name, SkirtAlgorithm.TOKEN),
            )
        }
        return SkirtInputs(
            SkirtInputs.Measurements(
                body = boundsList(context.bodyFrame),
                layers = layers,
                ribbonTargets = ribbonTargets,
                impulses = impulses,
                folder = tr("model.group.physics"),
                swayLabel = tr("model.physics.clothChain", SkirtAlgorithm.TOKEN),
                flareLabel = tr("model.physics.clothBounce", SkirtAlgorithm.TOKEN),
            ),
            rawContext,
        ).context()
    }

    fun ribbonsSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        meshesByLayer: Map<String, List<String>>,
        targetIds: Set<String>,
        skirtTargets: Set<String>,
        impulses: List<String>,
        rawContext: io.github.psd2live.core.modifier.ModifierAlgorithmContext,
    ): io.github.psd2live.core.modifier.ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val skeletonEnabled = config.rigEdits.skeleton?.enabled == true
        val layers = context.analysis.layers.mapNotNull { layer ->
            val kind = HairKind.clothOf(layer)?.takeIf { layer.opaquePixels > 0 } ?: return@mapNotNull null
            val meshes = if (kind != HairKind.SKIRT) meshesByLayer[layer.source.id.raw].orEmpty().filter { it in targetIds } else emptyList()
            val attachment = if (meshes.isEmpty()) null else {
                val (deformer, part, frameAndAccessory) = chainPartAttachment(layer, context, skeletonEnabled)
                RibbonsInputs.Attachment(deformer, part, frameAndAccessory.first, frameAndAccessory.second)
            }
            RibbonsInputs.Layer(
                id = layer.source.id.raw,
                tag = layer.semantic.tag.name,
                bounds = boundsList(layer.bounds),
                pixels = layer.opaquePixels,
                kind = ChainKind.valueOf(kind.name),
                meshes = meshes,
                attachment = attachment,
                warpName = tr("model.deformer.clothChain", layer.source.name),
                parameterName = tr("model.parameter.hairChain", layer.source.name, RibbonsAlgorithm.TOKEN),
            )
        }
        return RibbonsInputs(
            RibbonsInputs.Measurements(
                layers = layers,
                skirtTargets = skirtTargets,
                impulses = impulses,
                folder = tr("model.group.physics"),
                swayLabel = tr("model.physics.clothChain", RibbonsAlgorithm.TOKEN),
                bounceLabel = tr("model.physics.clothBounce", RibbonsAlgorithm.TOKEN),
            ),
            rawContext,
        ).context()
    }

    fun creatureSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        meshesByLayer: Map<String, List<String>>,
        targetIds: Set<String>,
        impulses: List<String>,
        rawContext: io.github.psd2live.core.modifier.ModifierAlgorithmContext,
    ): io.github.psd2live.core.modifier.ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val skeletonEnabled = config.rigEdits.skeleton?.enabled == true
        val layers = context.analysis.layers.mapNotNull { layer ->
            val kind = HairKind.partOf(layer)?.takeIf { layer.opaquePixels > 0 } ?: return@mapNotNull null
            val meshes = meshesByLayer[layer.source.id.raw].orEmpty().filter { it in targetIds }
            val attachment = if (meshes.isEmpty()) null else {
                val (deformer, part, frameAndAccessory) = chainPartAttachment(layer, context, skeletonEnabled)
                CreatureInputs.Attachment(listOf(deformer), part, frameAndAccessory.first, frameAndAccessory.second)
            }
            CreatureInputs.Layer(
                id = layer.source.id.raw,
                tag = layer.semantic.tag.name,
                bounds = boundsList(layer.bounds),
                pixels = layer.opaquePixels,
                kind = ChainKind.valueOf(kind.name),
                meshes = meshes,
                attachment = attachment,
                warpName = tr("model.deformer.partChain", layer.source.name),
                parameterName = tr("model.parameter.hairChain", layer.source.name, CreatureAlgorithm.TOKEN),
            )
        }
        return CreatureInputs(
            CreatureInputs.Measurements(
                layers = layers,
                impulses = impulses,
                folder = tr("model.group.physics"),
                swayLabel = tr("model.physics.partChain", CreatureAlgorithm.TOKEN),
                bounceLabel = tr("model.physics.partBounce", CreatureAlgorithm.TOKEN),
            ),
            rawContext,
        ).context()
    }

    fun eyeHighlightSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val eyes = context.fittedEyes(config)?.rigs.orEmpty()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val highlights = targetLayers.mapNotNull { (target, layerId) ->
            val layer = layers[layerId] ?: return@mapNotNull null
            if (layer.semantic.tag != SemanticTag.IRIDES || layer.semantic.role != PartRole.HIGHLIGHT || layer.opaquePixels == 0) {
                return@mapNotNull null
            }
            val eye = eyes[layer.semantic.side] ?: return@mapNotNull null
            val (parent, frame) = context.parentAndFrame(layer, config)
            val override = config.layerOverrides[layerId]
            val preset = (override?.type ?: layer.semantic.type) == LayerType.PRESET
            val automaticOpacity = preset || (override?.parameter ?: layer.semantic.parameter).isBlank()
            val opening = if (layer.semantic.side == Side.LEFT) StandardParameters.EYE_L_OPEN.raw else StandardParameters.EYE_R_OPEN.raw
            target to EyeHighlightInput(
                opening = opening,
                crease = eye.crease,
                irisWidth = (eye.fit.iris ?: layer.bounds).width,
                width = frame.width,
                height = frame.height,
                parent = parent?.raw?.let { parentId(target, it) },
                opacity = layer.source.opacity.coerceIn(0f, 1f),
                automaticOpacity = automaticOpacity,
            )
        }.toMap()
        return EyeHighlightInput.encode(highlights)
    }

    fun eyeLegacySourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        scaffoldContext: ModifierAlgorithmContext,
        primaryByLayer: Map<String, String>,
        layersByMesh: Map<String, String>,
        targetIds: List<String>,
        parameterNames: Map<String, String>,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), cache)
        val tuning = config.rigTuning
        val eyeTuning = EyeLegacyMotion.Tuning(
            tuning.blinkCorner, tuning.blinkDepth, tuning.eyelashSquash,
            tuning.irisJellySquash, tuning.irisJellyStretch, tuning.gazeX, tuning.gazeY,
        )
        val byLayer = context.analysis.layers.associateBy { it.source.id.raw }
        val ordinaryMasks = io.github.psd2live.core.modifier.RigOrdinaryMaterials.clipping(
            analysis.source, primaryByLayer.entries.associate { it.value to it.key },
        )
        val canonicalMeshes = scaffoldContext.values.getValue("mesh_inputs").jsonObject.getValue("materials").jsonArray
            .mapTo(HashSet()) { it.jsonObject.getValue("id").jsonPrimitive.content }
        val meshInputs = scaffoldMeshInputs(analysis, atlas, config, cache, primaryByLayer)
        val scaffoldResource = meshInputs.resources.getValue("source_meshes")
        val frames = ScaffoldFramesContext.build(scaffoldContext.values.getValue("frames").jsonObject)
        val branches = ScaffoldBranchesContext.build(scaffoldContext.values.getValue("branches").jsonObject, frames)
        val legacyLayers = LinkedHashMap<String, EyeLegacyInputs.Layer>()
        val opacity = LinkedHashMap<String, Float>()
        val openings = ArrayList<io.github.psd2live.algorithms.binding.EyeOpeningObservation>()
        for (targetId in targetIds) {
            val layerId = layersByMesh[targetId] ?: continue
            val layer = byLayer[layerId] ?: continue
            val canonical = primaryByLayer.getValue(layerId)
            if (layer.opaquePixels == 0 || canonical !in canonicalMeshes) continue
            opacity[canonical] = layer.source.opacity.coerceIn(0f, 1f)
            val aligned = context.rigLayer(layer)
            val (_, frame) = branches.parents.getValue(layerId)
            val masks = listOfNotNull(ordinaryMasks[layerId]?.raw)
            val tag = layer.semantic.tag
            val observation = if (tag == SemanticTag.EYEWHITE || tag == SemanticTag.IRIDES) {
                io.github.psd2live.algorithms.binding.EyeOpeningObservation(
                    layer.source.id.raw,
                    layer.source.id.raw,
                    layer.semantic.side,
                    layer.bounds,
                    layer.centroidX,
                    layer.centroidY,
                    layer.source.groupPath,
                    layer.semantic.parameter.takeIf { it.isNotBlank() },
                    layer.semantic.switchId,
                )
            } else null
            if (tag == SemanticTag.EYEWHITE && observation != null) openings.add(observation)
            val blink = if (tag == SemanticTag.EYEWHITE || tag == SemanticTag.EYELASH) {
                ScaffoldMesh.Geometry.Eye(
                    tag == SemanticTag.EYELASH,
                    aligned.bounds,
                    RigBuilder.matchingEyeWhiteBounds(aligned, context.eyeWhiteLayers),
                    aligned.centroidY,
                    layer.source.bounds.left,
                    if (tag == SemanticTag.EYELASH && context.faceRig.initialAngleZ == 0f) {
                        RigBuilder.eyelashCenterline(aligned)
                    } else null,
                    tuning.blinkCorner,
                    tuning.blinkDepth,
                    tuning.eyelashSquash,
                )
            } else null
            val iris = if (tag == SemanticTag.IRIDES) {
                ScaffoldMesh.Geometry.Iris(aligned.centroidX, aligned.centroidY, tuning.irisJellySquash, tuning.irisJellyStretch)
            } else null
            val closedDrawing = tag == SemanticTag.EYE_CLOSE
            if (blink != null || iris != null || closedDrawing) {
                legacyLayers[canonical] = EyeLegacyInputs.Layer(
                    layer.semantic.side, frame, blink, iris, closedDrawing, masks, observation,
                )
            }
        }
        val eyeInputs = EyeLegacyInputs(
            context.faceRig.centerX, eyeTuning, legacyLayers, scaffoldResource, openings,
        ).encode()
        return ModifierAlgorithmContext(
            kotlinx.serialization.json.buildJsonObject {
                put("scaffold", scaffoldContext.values)
                put("eye", eyeInputs.values)
                put("parameter_names", kotlinx.serialization.json.JsonObject(parameterNames.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                put("opacity", kotlinx.serialization.json.JsonObject(opacity.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                put("group_name", kotlinx.serialization.json.JsonPrimitive(tr("model.group.eyes")))
            },
            scaffoldContext.resources + mapOf("source_meshes" to scaffoldResource),
        )
    }

    fun browFittedSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        scaffoldId: String,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
        eyeProvider: String?,
        upstream: List<io.github.psd2live.core.modifier.ModifierUpstreamCapability>,
        frameConfig: PipelineConfig = config,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val frameContext = if (frameConfig == config) context else RigBuilder.rigContext(analysis, frameConfig, cache, neutralScaffold = true)
        val byLayer = context.analysis.layers.associateBy { it.source.id.raw }
        val capturedLayers = targetLayers.mapNotNull { (targetId, layerId) ->
            val layer = byLayer[layerId] ?: return@mapNotNull null
            val canonical = primaryByLayer[layerId] ?: return@mapNotNull null
            targetId to (canonical to layer)
        }.toMap()
        val inputs = io.github.psd2live.core.RigFittedBrowSource.capture(
            context, config, scaffoldId, capturedLayers, eyeProvider, frameContext, frameConfig,
        )
        val meshConfig = config.copy(recipe = io.github.psd2live.core.recipe.RigRecipe.Legacy.copy(
            modern = true,
            bases = io.github.psd2live.core.recipe.RigRecipe.Legacy.bases +
                ("brow" to io.github.psd2live.core.recipe.BaseChoice("brow/fitted")),
            moves = config.recipe.moves,
            eyeParts = config.recipe.eyeParts,
        ))
        val meshContext = if (meshConfig == config) context else RigBuilder.rigContext(analysis, meshConfig, cache, neutralScaffold = true)
        val topology = LinkedHashMap<String, ScaffoldMeshContext.Material>()
        for ((_, layerId) in targetLayers) {
            val layer = byLayer[layerId]?.takeIf { it.semantic.tag == SemanticTag.EYEBROW && it.opaquePixels > 0 } ?: continue
            val placement = atlas.placementByLayerId[layerId] ?: continue
            val (_, frame) = frameContext.parentAndFrame(layer, frameConfig)
            topology.getOrPut(layerId) {
                val mesh = RigBuilder.buildGridMesh(
                    layer, frame, meshContext.headSpaceFor(layer, meshConfig), meshConfig, cache,
                    MeshResolution.unitScale(meshConfig, meshContext.analysis.source),
                )
                val raw = ScaffoldMesh.RawMesh(
                    mesh.rigPositions,
                    LayerTexture.packed(layer, placement, atlas).bind(mesh.mesh.uvs),
                    mesh.mesh.indices,
                )
                ScaffoldMeshContext.Material(primaryByLayer.getValue(layerId), layerId, raw, ScaffoldMesh.Geometry.Zero)
            }
        }
        val vertices = ScaffoldMeshContext.capture(topology.values.toList()).resources.getValue("source_meshes")
        return inputs.encode(vertices, upstream)
    }

    fun browLegacySourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val byLayer = context.analysis.layers.associateBy { it.source.id.raw }
        val targets = targetLayers.mapNotNull { (targetId, layerId) ->
            val layer = byLayer[layerId]?.takeIf { it.semantic.tag == SemanticTag.EYEBROW && it.opaquePixels > 0 } ?: return@mapNotNull null
            val (parent, frame) = context.parentAndFrame(layer, config)
            val aligned = context.rigLayer(layer)
            targetId to BrowLegacyInputs.Brow(
                layer.semantic.side,
                aligned.bounds.height,
                frame.height,
                parent?.raw?.let { parentId(targetId, it) },
            )
        }.toMap()
        return BrowLegacyInputs(
            targets = targets,
            lift = config.rigTuning.browLift,
            leftName = tr("model.parameter.browLY"),
            rightName = tr("model.parameter.browRY"),
            groupName = tr("model.group.brows"),
        ).encode()
    }

    fun browLidSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
        browMeshes: List<String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val wide = eyeWideSourceInputs(analysis, atlas, config, cache, targetLayers, parentId)
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val byLayer = context.analysis.layers.associateBy { it.source.id.raw }
        val carrierNames = kotlinx.serialization.json.buildJsonObject {
            for ((targetId, layerId) in targetLayers) {
                val layer = byLayer[layerId] ?: continue
                put(targetId, kotlinx.serialization.json.JsonPrimitive(tr("model.deformer.blendShapeCarrier", layer.source.name)))
            }
        }
        val nativeIds = kotlinx.serialization.json.buildJsonObject {
            for ((targetId, layerId) in targetLayers) {
                primaryByLayer[layerId]?.let { put(targetId, kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        }
        val browMeshesJson = kotlinx.serialization.json.JsonArray(browMeshes.map { kotlinx.serialization.json.JsonPrimitive(it) })
        return ModifierAlgorithmContext(
            kotlinx.serialization.json.JsonObject(
                wide.values + mapOf(
                    "carrier_names" to carrierNames,
                    "native_ids" to nativeIds,
                    "brow_cover" to kotlinx.serialization.json.JsonPrimitive(context.browCover),
                    "brow_meshes" to browMeshesJson,
                ),
            ),
            wide.resources,
        )
    }

    fun browBlinkSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        eyeProvider: String?,
        parentId: (String, String) -> String,
        frameConfig: PipelineConfig = config,
    ): ModifierAlgorithmContext {
        val shape = browShapeSourceInputs(analysis, atlas, config, cache, targetLayers, eyeProvider, parentId, frameConfig).encode()
        val shapeTargets = shape.values.getValue("targets").jsonObject.keys
        val byLayer = analysis.layers.associateBy { it.source.id.raw }
        val carrierNames = kotlinx.serialization.json.buildJsonObject {
            for ((targetId, layerId) in targetLayers) {
                if (targetId !in shapeTargets) continue
                val layer = byLayer[layerId] ?: continue
                put(targetId, kotlinx.serialization.json.JsonPrimitive(tr("model.deformer.blendShapeCarrier", layer.source.name)))
            }
        }
        return ModifierAlgorithmContext(
            kotlinx.serialization.json.JsonObject(shape.values + ("carrier_names" to carrierNames)),
            shape.resources,
        )
    }

    fun faceLegacySourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        scaffoldContext: ModifierAlgorithmContext,
        strength: Float,
        tuning: io.github.psd2live.core.RigTuning,
        parameterNames: List<String>,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val face = context.faceRig
        return LegacyFaceInputs.capture(
            scaffoldContext,
            face.centerY,
            face.radiusX,
            face.radiusY,
            face.eyeLineY,
            tuning.legacyFaceTuning(),
            strength,
            parameterNames,
            tr("model.group.face"),
        )
    }

    fun depthTurnSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        scaffoldContext: ModifierAlgorithmContext,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val framesJson = scaffoldContext.values.getValue("frames").jsonObject
        val branchesJson = scaffoldContext.values.getValue("branches").jsonObject
        val frames = ScaffoldFramesContext.build(framesJson)
        val branches = ScaffoldBranchesContext.build(branchesJson, frames)
        val headFrame = ScaffoldFramesContext.decode(framesJson).head
        val frameById = ScaffoldHead.build(headFrame, { it }).frames + branches.parents.values.associate { it.id to it.frame }
        val byLayer = context.analysis.layers.associateBy { it.source.id.raw }
        val layers = LinkedHashMap<String, ClassifiedLayer>()
        val parents = LinkedHashMap<String, org.umamo.runtime.model.DeformerId?>()
        val scaffoldMeshIds = LinkedHashMap<String, String>()
        for ((targetId, layerId) in targetLayers) {
            val layer = byLayer[layerId] ?: continue
            val canonical = primaryByLayer[layerId] ?: continue
            layers[targetId] = layer
            parents[targetId] = branches.parents[layerId]?.id
            scaffoldMeshIds[targetId] = canonical
        }
        return io.github.psd2live.core.RigDepthTurnSource.capture(
            context, config, layers, parents, frameById,
            scaffoldContext.resources["source_meshes"], scaffoldMeshIds, headFrame,
        ).encode()
    }

    fun faceFittedSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        scaffoldContext: ModifierAlgorithmContext,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
        strength: Float,
        tuning: io.github.psd2live.core.RigTuning,
        parameterNames: List<String>,
        upstream: List<io.github.psd2live.core.modifier.ModifierUpstreamCapability>,
    ): ModifierAlgorithmContext {
        val base = faceLegacySourceInputs(analysis, config, cache, scaffoldContext, strength, tuning, parameterNames)
        val turn = depthTurnSourceInputs(analysis, config, cache, scaffoldContext, targetLayers, primaryByLayer)
        return ModifierAlgorithmContext(
            kotlinx.serialization.json.buildJsonObject {
                put("base", base.values)
                put("turn", turn.values)
            },
            base.resources + turn.resources,
            upstream,
        )
    }

    fun bustSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        scaffoldContext: ModifierAlgorithmContext,
        torso: TorsoFrame,
        materialBase: org.umamo.runtime.model.PuppetModel,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
    ): ModifierAlgorithmContext {
        val byLayer = analysis.layers.associateBy { it.source.id.raw }
        val excludedRoles = setOf(
            io.github.psd2live.core.PartRole.SLEEVE,
            io.github.psd2live.core.PartRole.SKIRT,
            io.github.psd2live.core.PartRole.RIBBON,
            io.github.psd2live.core.PartRole.PENDANT,
        )
        val layers = LinkedHashMap<String, BustBindingInputs.Layer>()
        val targets = LinkedHashMap<String, String>()
        val baseIr = io.github.psd2live.targets.cubism.PuppetIr.toIr(materialBase)
        val meshTiles = baseIr.meshes.associate { it.id to it.tile }
        for ((targetId, layerId) in targetLayers) {
            val layer = byLayer[layerId] ?: continue
            if (layer.semantic.tag != SemanticTag.TOPWEAR && layer.semantic.tag != SemanticTag.NECKWEAR) continue
            if (layer.semantic.role in excludedRoles) continue
            val canonical = primaryByLayer[layerId] ?: continue
            layers[canonical] = BustBindingInputs.Layer(
                layerId,
                layer.semantic.role == io.github.psd2live.core.PartRole.RIGID,
                meshTiles[canonical] ?: io.github.psd2live.core.PuppetSourceAtlas.tileIdFor(layerId).raw,
            )
            targets[targetId] = canonical
        }
        val textureSpace = BustBindingInputs.TextureSpace(baseIr.textures, baseIr.authoring)
        val available = setOf(
            "ParamBustY", "ParamBustX", "ParamBodyAngleY", "ParamAngleY", "ParamBreath", "ParamBodyAngleX", "ParamBodyAngleZ",
        )
        val physics = io.github.psd2live.core.PhysicsGenerator.presetRules(
            io.github.psd2live.core.PhysicsGenerator.Presets.All, available,
        ).filter { it.id in setOf("PhysicsBustY", "PhysicsBustX") }.map {
            io.github.psd2live.algorithms.physics.PhysicsGroups.fromSetting(it)
        }
        val allMeshes = ScaffoldMeshContext.decodeRawMeshes(scaffoldContext.resources.getValue("source_meshes"))
        val bustMeshes = ScaffoldMeshContext.capture(layers.map { (canonical, layer) ->
            ScaffoldMeshContext.Material(canonical, layer.sourceId, allMeshes.getValue(canonical), ScaffoldMesh.Geometry.Zero)
        }).resources.getValue("source_meshes")
        return BustBindingInputs(
            BustBindingInputs.Torso(torso.centerX, torso.shoulderY, torso.waistY, torso.halfWidth),
            layers,
            targets,
            scaffoldContext.values.getValue("frames").jsonObject,
            scaffoldContext.values.getValue("branches").jsonObject,
            bustMeshes,
            textureSpace,
            BustBindingInputs.Names(tr("model.parameter.bustY"), tr("model.parameter.bustX"), tr("model.group.physics")),
            physics,
            applies = io.github.psd2live.core.recipe.RigModules.BODY_BUST.applies(
                io.github.psd2live.core.recipe.RecipeSubject.of(analysis),
            ),
        ).encode()
    }

    fun armSwingSourceInputs(
        scaffoldContext: ModifierAlgorithmContext,
        torso: TorsoFrame,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
    ): ModifierAlgorithmContext {
        val targets = kotlinx.serialization.json.buildJsonObject {
            for ((targetId, layerId) in targetLayers) {
                primaryByLayer[layerId]?.let { put(targetId, kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        }
        val values = kotlinx.serialization.json.buildJsonObject {
            put("schema", io.github.psd2live.algorithms.binding.ArmSwingAlgorithm.SCHEMA)
            put("torso", kotlinx.serialization.json.JsonArray(listOf(torso.centerX, torso.shoulderY, torso.waistY, torso.halfWidth).map {
                kotlinx.serialization.json.JsonPrimitive(it)
            }))
            put("frames", scaffoldContext.values.getValue("frames"))
            put("branches", scaffoldContext.values.getValue("branches"))
            put("targets", targets)
            put("parameter_names", kotlinx.serialization.json.buildJsonObject {
                put("L", tr("model.parameter.armLSwing"))
                put("R", tr("model.parameter.armRSwing"))
            })
            put("folder", tr("model.group.physics"))
            put("physics_names", kotlinx.serialization.json.buildJsonObject {
                put("L", tr("model.physics.armSwing", "L"))
                put("R", tr("model.physics.armSwing", "R"))
            })
        }
        return ModifierAlgorithmContext(values)
    }

    private fun toModelBounds(b: Bounds) = io.github.psd2live.algorithms.binding.Bounds(b.left, b.top, b.right, b.bottom)

    fun hairLegacySourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        materialBase: PuppetModel,
        targetLayers: Map<String, String>,
        primaryByLayer: Map<String, String>,
        parentId: (String, String) -> String,
        earlierBinding: Boolean,
        frontHairSway: Boolean,
        backHairSway: Boolean,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val pivotCanvas = context.faceRig.coordinateSpace.toCanvas(context.faceRig.centerX, context.faceRig.mouthLineY)
        val pivot = ScaffoldHead.Point(pivotCanvas.first, pivotCanvas.second)
        val headBounds = toModelBounds(context.head)
        val chinY = context.face.bottom
        fun tuningFor(plane: HairLegacyMotion.Plane, t: io.github.psd2live.core.RigTuning) = when (plane) {
            HairLegacyMotion.Plane.FRONT -> HairLegacyMotion.Tuning(t.frontHairTurn, t.frontHairTilt, t.frontHairPerspective, t.frontHairSway, t.frontHairCurl, t.hairRollHold)
            HairLegacyMotion.Plane.BACK -> HairLegacyMotion.Tuning(t.backHairTurn, t.backHairTilt, 0f, t.backHairSway, t.backHairCurl, t.hairRollHold)
        }
        val defaults = io.github.psd2live.core.RigTuning()
        val planes = linkedMapOf<HairLegacyMotion.Plane, HairLegacyInputs.Source>()
        context.frontHair?.let { frame ->
            planes[HairLegacyMotion.Plane.FRONT] = HairLegacyInputs.Source(
                HairLegacyMotion.Frame(toModelBounds(frame), headBounds, pivot, chinY),
                tuningFor(HairLegacyMotion.Plane.FRONT, config.rigTuning),
                tuningFor(HairLegacyMotion.Plane.FRONT, defaults),
            )
        }
        context.backHair?.let { frame ->
            planes[HairLegacyMotion.Plane.BACK] = HairLegacyInputs.Source(
                HairLegacyMotion.Frame(toModelBounds(frame), headBounds, pivot, chinY),
                tuningFor(HairLegacyMotion.Plane.BACK, config.rigTuning),
                tuningFor(HairLegacyMotion.Plane.BACK, defaults),
            )
        }
        val parameters = buildList {
            if (frontHairSway) {
                add(HairLegacyInputs.parameter(HairLegacyMotion.Plane.FRONT, tr("model.parameter.hairFront")))
            }
            if (backHairSway) {
                add(HairLegacyInputs.parameter(HairLegacyMotion.Plane.BACK, tr("model.parameter.hairBack")))
            }
        }
        val byLayer = context.analysis.layers.associateBy { it.source.id.raw }
        val neutralByDrawable = materialBase.drawables.associate { it.id.raw to it.mesh }
        val targets = linkedMapOf<String, HairLegacyInputs.Target>()
        for ((targetId, layerId) in targetLayers) {
            val layer = byLayer[layerId]?.takeIf { it.opaquePixels > 0 } ?: continue
            val plane = when (layer.semantic.tag) {
                SemanticTag.FRONT_HAIR -> HairLegacyMotion.Plane.FRONT
                SemanticTag.BACK_HAIR -> HairLegacyMotion.Plane.BACK
                else -> continue
            }
            if (plane !in planes) continue
            val swayEnabled = if (plane == HairLegacyMotion.Plane.FRONT) frontHairSway else backHairSway
            targets[targetId] = HairLegacyInputs.Target(
                plane = plane,
                follow = parentId(targetId, plane.follow.raw),
                sway = if (swayEnabled) parentId(targetId, plane.sway.raw) else null,
                head = parentId(targetId, ScaffoldHead.headId.raw),
                earlierBinding = earlierBinding,
                activeTurnAxes = HairLegacyMotion.turnAxes,
                fittedFace = parentId(targetId, "DeformFaceFit"),
                neutralMesh = primaryByLayer[layerId]?.let { neutralByDrawable[it] }?.let(HairLegacyInputs::meshIdentity),
            )
        }
        return HairLegacyInputs.capture(HairLegacyInputs.Input(
            planes = planes,
            targets = targets,
            parameters = parameters,
            physicsFolderName = tr("model.group.physics"),
        ))
    }

    fun hairChainsSourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        meshesByLayer: Map<String, List<String>>,
        targetIds: Set<String>,
        frontHairSway: Boolean,
        backHairSway: Boolean,
        expressionInputs: List<String>,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val pivotCanvas = context.faceRig.coordinateSpace.toCanvas(context.faceRig.centerX, context.faceRig.mouthLineY)
        val head = HairFollowMotion.Head(toModelBounds(context.head), pivotCanvas.first, pivotCanvas.second, context.face.bottom)
        val planes = linkedMapOf<String, HairChainsInputs.Plane>()
        context.frontHair?.let { frame ->
            planes["Front"] = HairChainsInputs.Plane(
                frame = toModelBounds(frame),
                follow = "DeformHairFrontFollow",
                sway = "DeformHairFrontPhysics".takeIf { frontHairSway },
                part = "PartHairFront",
            )
        }
        context.backHair?.let { frame ->
            planes["Back"] = HairChainsInputs.Plane(
                frame = toModelBounds(frame),
                follow = "DeformHairBackFollow",
                sway = "DeformHairBackPhysics".takeIf { backHairSway },
                part = "PartHairBack",
            )
        }
        val hairLayers = context.analysis.layers.filter { layer ->
            layer.opaquePixels > 0 && (layer.semantic.tag == SemanticTag.FRONT_HAIR || layer.semantic.tag == SemanticTag.BACK_HAIR)
        }
        val pairCandidates = hairLayers.filter { layer ->
            layer.semantic.side == Side.LEFT || layer.semantic.side == Side.RIGHT
        }.mapNotNull { layer ->
            val planeName = if (layer.semantic.tag == SemanticTag.FRONT_HAIR) "Front" else "Back"
            val plane = planes[planeName] ?: return@mapNotNull null
            val parent = DeformerId(plane.sway ?: plane.follow)
            ScaffoldPairs.Layer(
                layer.source.id.raw, layer.source.name, layer.semantic.tag.name, layer.semantic.side,
                context.rigLayer(layer).bounds, parent, plane.frame, PartId(plane.part),
            )
        }
        val paired = ScaffoldPairs.build(pairCandidates, emptySet(), { tr("model.deformer.pair", it) })
            .flatMapTo(HashSet()) { it.layerIds }
        val layers = hairLayers.mapNotNull { layer ->
            val planeName = if (layer.semantic.tag == SemanticTag.FRONT_HAIR) "Front" else "Back"
            if (planeName !in planes) return@mapNotNull null
            val kind = HairKind.of(layer)?.let { ChainKind.valueOf(it.name) }
            val meshes = meshesByLayer[layer.source.id.raw].orEmpty().filter { it in targetIds }
            HairChainsInputs.Layer(
                sourceId = layer.source.id.raw,
                plane = planeName,
                bounds = toModelBounds(context.rigLayer(layer).bounds),
                pixels = layer.opaquePixels,
                kind = kind,
                shading = layer.semantic.role == PartRole.SHADOW || layer.semantic.role == PartRole.HIGHLIGHT,
                paired = layer.source.id.raw in paired,
                meshes = meshes,
                warpName = tr("model.deformer.hairChain", layer.source.name),
                parameterName = tr("model.parameter.hairChain", layer.source.name, HairChainsAlgorithm.NAME_TOKEN),
            )
        }
        val physicsLabels = listOf(
            "model.physics.hairChain",
            "model.physics.hairBounce",
            "model.physics.clothChain",
            "model.physics.clothBounce",
            "model.physics.partChain",
            "model.physics.partBounce",
        ).associateWith { tr(it, HairChainsAlgorithm.NAME_TOKEN) }
        return HairChainsInputs(
            head = head,
            eyeY = context.faceRig.eyeLineY,
            radiusX = context.faceRig.radiusX,
            radiusY = context.faceRig.radiusY,
            planes = planes,
            layers = layers,
            physicsFolder = tr("model.group.physics"),
            physicsLabels = physicsLabels,
            expressionInputs = expressionInputs,
        ).context()
    }

    fun mouthLegacySourceInputs(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, ClassifiedLayer>,
        primaryByLayer: Map<String, String>,
        scaffoldContext: ModifierAlgorithmContext,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val tuning = config.rigTuning
        val mouthTags = setOf(
            SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
            SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE,
        )
        val layers = targetLayers.entries.mapNotNull { (id, original) ->
            val layer = context.rigLayer(original)
            if (layer.semantic.tag !in mouthTags) return@mapNotNull null
            val tag = layer.semantic.tag.name
            val aperture = RigBuilder.mouthApertureFor(layer) ?: layer.bounds
            val frame = context.parentAndFrame(original, config).second
            val maskSource = layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
                !layer.source.clipped && layer.semantic.role == null
            id to io.github.psd2live.algorithms.binding.MouthLegacyInputs.Layer(
                canonical = primaryByLayer[original.source.id.raw] ?: id,
                tag = tag,
                side = io.github.psd2live.algorithms.binding.Side.valueOf(layer.semantic.side.name),
                variant = layer.semantic.variant,
                frame = toModelBounds(frame),
                aperture = toModelBounds(aperture),
                bounds = toModelBounds(layer.bounds),
                preset = (config.layerOverrides[original.source.id.raw]?.type ?: original.semantic.type) == LayerType.PRESET,
                opacity = layer.source.opacity,
                originalMask = null,
                maskSource = maskSource,
            )
        }.toMap()
        val motion = io.github.psd2live.algorithms.binding.MouthFittedInputs.Fallback(
            curve = if (config.mouthShape == "custom") config.mouthCurve else io.github.psd2live.algorithms.binding.MouthCurve.preset(config.mouthShape),
            exactClose = false,
            closedNarrow = tuning.mouthClosedNarrow,
            formWiden = tuning.mouthFormWiden,
            seam = tuning.mouthSeam,
            smileBase = tuning.mouthSmileBase,
            smile = tuning.mouthSmile,
        )
        return io.github.psd2live.algorithms.binding.MouthLegacyInputs(
            layers = layers,
            motion = motion,
            teethFade = tuning.teethFade,
            closedMouth = context.hasClosedMouth,
            formName = tr("model.parameter.mouthForm"),
            openName = tr("model.parameter.mouthOpen"),
            groupName = tr("model.group.mouth"),
            scaffold = scaffoldContext,
        ).encode()
    }

    fun mouthCheekLidSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, String>,
        parameterNames: Map<String, String>,
        parentId: (String, String) -> String,
    ): ModifierAlgorithmContext {
        val smile = eyeSmileSourceInputs(analysis, atlas, config, cache, targetLayers, parameterNames, parentId)
        val byLayerId = analysis.layers.associateBy { it.source.id.raw }
        val carriers = buildJsonObject {
            for ((id, layerId) in targetLayers) {
                val layer = byLayerId[layerId] ?: continue
                putJsonObject(id) {
                    put("id", id)
                    put("name", tr("model.deformer.meshShape", layer.source.name))
                }
            }
        }
        return ModifierAlgorithmContext(
            values = buildJsonObject {
                put("schema", "mouth-cheek-lid/1")
                put("source", smile.values)
                put("carriers", carriers)
            },
            resources = smile.resources,
        )
    }

    fun mouthJawSourceInputs(
        analysis: PipelineAnalysis,
        atlas: PackedAtlas,
        config: PipelineConfig,
        cache: PreviewMeshCache,
        targetLayers: Map<String, ClassifiedLayer>,
        primaryByLayer: Map<String, String>,
        providers: Map<String, String>,
    ): ModifierAlgorithmContext {
        val context = RigBuilder.rigContext(analysis, config, cache, neutralScaffold = true)
        val fitted = context.fittedMouth(config)
        val fit = fitted?.source?.fit
        val mouthBox = analysis.layers.firstOrNull {
            it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && it.semantic.role == null
        }?.bounds ?: analysis.anchors.face
        val originX = fit?.originX ?: mouthBox.centerX
        val originY = fit?.originY ?: mouthBox.centerY
        val axisX = fit?.axisX ?: 1f
        val axisY = fit?.axisY ?: 0f
        val width = fit?.width ?: mouthBox.width.coerceAtLeast(1f)
        val opening = fit?.opening ?: mouthBox.height.coerceAtLeast(1f)
        val chin = fitted?.chin ?: 0.6f
        val reach = if (config.recipe.base("mouth") === io.github.psd2live.core.recipe.RigModules.MOUTH_FITTED) {
            config.recipe.baseValue("mouth", "reach") / 100f
        } else 1f
        val targetsJson = buildJsonObject {
            for ((id, layer) in targetLayers) {
                val (parent, frame) = context.parentAndFrame(layer, config)
                putJsonObject(id) {
                    put("frame", JsonArray(listOf(frame.left, frame.top, frame.right, frame.bottom).map(::JsonPrimitive)))
                    put("nose", layer.semantic.tag == SemanticTag.NOSE)
                    put("parent", parent?.raw?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        val raw = ScaffoldMeshContext.capture(targetLayers.values.distinctBy { it.source.id }.map { layer ->
            val data = RigBuilder.fittedMouthSourceMesh(layer, context, config, atlas, cache)
            ScaffoldMeshContext.Material(
                primaryByLayer.getValue(layer.source.id.raw),
                layer.source.id.raw,
                io.github.psd2live.algorithms.binding.ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices),
                io.github.psd2live.algorithms.binding.ScaffoldMesh.Geometry.Zero,
            )
        })
        return ModifierAlgorithmContext(
            values = buildJsonObject {
                put("schema", "mouth-jaw/1")
                putJsonObject("providers") {
                    for ((k, v) in providers) put(k, v)
                }
                put("targets", targetsJson)
                put("fit", JsonArray(listOf(originX, originY, axisX, axisY, width, opening).map(::JsonPrimitive)))
                put("chin", chin)
                put("reach", reach)
            },
            resources = raw.resources,
        )
    }
}
