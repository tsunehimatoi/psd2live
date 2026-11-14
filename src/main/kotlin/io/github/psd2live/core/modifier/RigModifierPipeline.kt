package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.bridge.PuppetIr
import java.util.concurrent.CancellationException
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.PuppetModel

internal data class ModifierBaseSnapshot(val key: List<Any>, val model: RigPreviewModel, val input: RigIR)

/** The single preview/export boundary for documents whose presets are explicit instances. */
internal object RigModifierPipeline {
    /** Reconstruct the ordinary input from the captured author state, never from an already evaluated stack. */
    fun input(model: RigPreviewModel): RigIR {
        val config = ordinaryConfig(model.config)
        val authored = model.authored.finished(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
        return RigIrCompiler.compileAuthored(model.modifierAuthorAnalysis ?: model.analysis, model.modifierAuthorAtlas ?: model.atlas, authored, model.config)
    }

    /** Ordinary content may contain custom motions and physics, but no legacy preset runs implicitly. */
    fun ordinaryConfig(config: PipelineConfig): PipelineConfig = config.copy(
        rigEdits = config.rigEdits.copy(modifierStack = null),
        recipe = RigRecipe.Legacy, generateDeformers = false,
        featureDisplacementEnabled = false, mouthOutlineEnabled = false,
        seeThroughBrows = false, seeThroughEyes = false,
        physicsFrontHair = false, physicsBackHair = false, physicsEyeJelly = false,
        hairSimulationFront = false, hairSimulationBack = false,
        motionBasic = false, motionSkeleton = false,
    )

    fun build(pipeline: PSD2LivePipeline, artwork: SourceArt, config: PipelineConfig,
              progress: ProgressListener, previousAtlas: PackedAtlas?): RigPreviewModel {
        val stack = RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack))
        require(config.rigEdits.swingEdits.isEmpty() && config.rigEdits.simEdits.isEmpty() && config.rigEdits.interiorEffects.isEmpty()) {
            "Legacy generators must be converted into explicit modifiers before enabling the stack"
        }
        val ordinary = ordinaryConfig(config)
        // Mesh-only is a construction input here, not an export gate on the ordinary authored content.
        val baseKey = listOf(artwork, ordinary, config.meshOnly && stack.instances.none { it.enabled } && stack.appliedCapabilities.isEmpty(), io.github.psd2live.i18n.I18n.currentLanguage)
        val retained = pipeline.modifierBase?.takeIf { it.key == baseKey }
        val base = retained?.model ?: RigBuildProfile.stage("modifier author input") {
            pipeline.buildOrdinaryPreview(artwork, ordinary.copy(meshOnly = true),
                ProgressListener { message, fraction -> progress.update(message, fraction * .8) }, previousAtlas)
        }
        if (retained != null) RigBuildProfile.count("modifier author input cache hits")
        val input = retained?.input ?: RigIrCompiler.compileAuthored(base.analysis, base.atlas, base.rig, config)
        val source = RigModifierSource(base.analysis, base.atlas, config, base.rig.layerIdByDrawableId, base.rig.copiedDrawableIds, base.rig.puppet, pipeline.meshCache)
        // Saving the initial automatic layout as an explicit arrangement is presentation bookkeeping.
        // Compare the resolved layout and let the evaluator compare the complete authored IR, including
        // page content, before reusing a prefix or its captured inputs.
        val context = listOf(artwork, config.copy(atlasArrangement = null, rigEdits = config.rigEdits.copy(modifierStack = null)),
            base.atlas.placementByLayerId, base.atlas.fit, base.atlas.footprints, io.github.psd2live.i18n.I18n.currentLanguage, pipeline.rawSourceRotations)
        val result = ModifierPluginStore.shared.withArchivedBundles(stack.pluginReferences()) {
            RigModifierAlgorithms.evaluator(source, stack, pipeline.rawSourceRotations).evaluate(
                input, stack, pipeline.modifierCache, context,
                reuseCapturedAuthorInputs = true) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("Modifier evaluation cancelled")
                progress.update("Evaluating modifier stack", .85)
            }
        }
        val evaluated = base.rig.withModifierMeshes(PuppetIr.toPuppet(result.rig), result.rig.textures.tileArt)
            .copy(modifierOutput = result.rig, modifierReport = result.report())
        val extra = RigModifierRasterAssets.layers(evaluated, config).filterNot { it.source.id.raw in base.atlas.placementByLayerId }
        val appended = RigModifierRasterAssets.append(base.atlas, extra, config)
        val arranged = RigModifierRasterAssets.arranged(extra, config)
        val composed = when {
            extra.isEmpty() -> base.analysis
            arranged -> base.analysis.copy(layers = RigModifierRasterAssets.sourceOrder(base.analysis.layers, extra))
            else -> base.analysis.copy(layers = base.analysis.layers + extra)
        }
        val atlas = if (arranged) AtlasLayout.pack(composed.layers, config, previous = previousAtlas) else appended
        val rig = if (arranged) requireNotNull(RigLayerDeletion.rebind(evaluated, composed, appended, composed, atlas)) {
            "The arranged atlas cannot bind every generated mesh"
        } else evaluated
        progress.update("Compiling modifier result", .95)
        val bundle = pipeline.previewBundle(composed, atlas, rig, config)
        return base.copy(analysis = composed, atlas = atlas, rig = rig, config = config, runtimeBundle = bundle,
            modifierAuthorAtlas = base.atlas, modifierAuthorAnalysis = base.analysis).also {
                it.modifierEvaluation = pipeline.modifierCache.snapshot
                pipeline.modifierBase = ModifierBaseSnapshot(baseKey, base, input)
                it.modifierBaseSnapshot = pipeline.modifierBase
                it.modifierMeshCache = pipeline.meshCache
            }
    }
}

/** A generated copy uses its original's source art and atlas page in preview, export and checkpoints. */
internal fun BuiltRig.withModifierMeshes(model: PuppetModel, assets: List<TileArt> = modifierRasterAssets): BuiltRig {
    val generated = model.drawables.filter { it.id.raw !in layerIdByDrawableId && it.textureSourceId != null }
    val layers = layerIdByDrawableId.toMutableMap()
    val pages = pageByDrawableId.toMutableMap()
    val bounds = sourceBoundsByDrawableId.toMutableMap()
    for (mesh in generated) {
        val original = requireNotNull(mesh.textureSourceId).raw
        layers[original]?.let { layers[mesh.id.raw] = it }
        pages[original]?.let { pages[mesh.id.raw] = it }
        bounds[original]?.let { bounds[mesh.id.raw] = it }
    }
    val assetTiles = assets.mapTo(HashSet()) { it.tile }
    for (mesh in model.drawables) {
        val tileId = mesh.atlasTileId ?: continue
        if (tileId.raw !in assetTiles || mesh.id.raw in layers) continue
        val tile = model.atlas.tileById.getValue(tileId)
        val source = requireNotNull(tile.source)
        val layer = model.sources.single { it.id == source.sourceId }.layers.single { it.key == source.layerKey }
        layers[mesh.id.raw] = layer.key
        pages[mesh.id.raw] = requireNotNull(tile.placement).pageIndex
        bounds[mesh.id.raw] = Bounds(layer.left.toFloat(), layer.top.toFloat(),
            (layer.left + layer.width).toFloat(), (layer.top + layer.height).toFloat())
    }
    return copy(puppet = model, layerIdByDrawableId = layers, pageByDrawableId = pages,
        sourceBoundsByDrawableId = bounds, copiedDrawableIds = copiedDrawableIds + generated.map { it.id.raw },
        modifierRasterAssets = assets)
}
