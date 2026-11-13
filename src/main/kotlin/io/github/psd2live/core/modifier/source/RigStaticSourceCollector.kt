package io.github.psd2live.core.modifier.source

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.BodyStance
import io.github.psd2live.algorithms.binding.BrowShapeInputs
import io.github.psd2live.algorithms.binding.BrowSpringInputs
import io.github.psd2live.algorithms.binding.ChainKind
import io.github.psd2live.algorithms.binding.CreatureAlgorithm
import io.github.psd2live.algorithms.binding.CreatureInputs
import io.github.psd2live.algorithms.binding.EyeFittedInputs
import io.github.psd2live.algorithms.binding.EyeSmileInputs
import io.github.psd2live.algorithms.binding.EyeSquashInputs
import io.github.psd2live.algorithms.binding.EyeWideInputs
import io.github.psd2live.algorithms.binding.LidFadeInputs
import io.github.psd2live.algorithms.binding.NeckInputs
import io.github.psd2live.algorithms.binding.RibbonsAlgorithm
import io.github.psd2live.algorithms.binding.RibbonsInputs
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.algorithms.binding.SkirtAlgorithm
import io.github.psd2live.algorithms.binding.SkirtInputs
import io.github.psd2live.algorithms.binding.TorsoFrame
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
import kotlinx.serialization.json.JsonObject

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
    ): BrowShapeInputs {
        val context = RigBuilder.rigContext(analysis, config, cache)
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
    ): BrowSpringInputs {
        val context = RigBuilder.rigContext(analysis, config, cache)
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
        val sourceParents = RigBuilder.faceTurnAndLean(context, config, RigBuilder.stagesOf(cache)).associateBy { it.id }
        val parameters = RigBuilder.standardParameters(context, config) + RigBuilder.moduleParameters(context, config, emptyList())
        val targets = targetLayers.mapNotNull { (id, layerId) ->
            val layer = layers[layerId]?.takeIf {
                it.semantic.tag == SemanticTag.EYEBROW && it.semantic.role == null && it.opaquePixels > 0
            } ?: return@mapNotNull null
            val fit = fits[layer.semantic.side] ?: return@mapNotNull null
            val placement = atlas.placementByLayerId[layerId] ?: return@mapNotNull null
            val (parent, frame) = context.parentAndFrame(layer, config)
            val data = RigBuilder.buildGridMesh(
                layer, frame, context.headSpaceFor(layer, config), config, cache,
                MeshResolution.unitScale(config, context.analysis.source),
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
                BrowSpringInputs.fingerprint(when (ref) {
                    is Deformer.Warp -> ref.copy(id = mappedId, parent = mappedParent)
                    is Deformer.Rotation -> ref.copy(id = mappedId, parent = mappedParent)
                })
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
}
