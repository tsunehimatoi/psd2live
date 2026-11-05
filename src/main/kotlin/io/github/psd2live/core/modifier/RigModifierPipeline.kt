package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.targets.cubism.PuppetIr
import java.util.concurrent.CancellationException
import org.umamo.runtime.model.PuppetModel

/** The single preview/export boundary for documents whose presets are explicit instances. */
internal object RigModifierPipeline {
    /** Reconstruct the ordinary input from the captured author state, never from an already evaluated stack. */
    fun input(model: RigPreviewModel): RigIR {
        val config = ordinaryConfig(model.config)
        val authored = model.authored.finished(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
        return compileOrdinary(model.modifierAuthorAnalysis ?: model.analysis, model.modifierAuthorAtlas ?: model.atlas, authored, model.config)
    }

    /** Compile authored sidecars without inferring generated physics from the names of ordinary parameters. */
    fun compileOrdinary(analysis: PipelineAnalysis, atlas: PackedAtlas, rig: BuiltRig, config: PipelineConfig,
                        tileArt: Boolean = false, simulations: Boolean = false): RigIR {
        val stack = config.rigEdits.modifierStack?.let(RigModifierStack::fromJson)
        val meshOnly = config.meshOnly && stack?.let { it.instances.none { entry -> entry.enabled } && it.appliedCapabilities.isEmpty() } == true
        val ordinary = ordinaryConfig(config).copy(meshOnly = meshOnly, generatePhysics = false)
        val compiled = RigIrCompiler.compile(analysis, atlas, rig.copy(modifierOutput = null), ordinary, tileArt, simulations)
        val available = rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
        val claimed = HashSet<String>()
        val groups = PhysicsCatalog.ordered(config.rigEdits.physicsEdits, config.rigEdits.physicsOrder) { it.id }
            .filter { it.id !in config.rigEdits.disabledPhysicsIds && PhysicsCatalog.issueOf(it, available) == null }
            .map { group ->
                require(group.outputParameters.all { claimed.add(it) }) { "Ordinary physics groups have conflicting outputs" }
                Physics3Json.group(group)
            }
        val textures = if (tileArt) compiled.textures else compiled.textures.copy(tileArt = rig.modifierRasterAssets)
        return compiled.copy(textures = textures, physics = Physics(groups, config.rigEdits.physicsFps.toFloat()),
            restPose = if (meshOnly) compiled.restPose else emptyMap(),
            parameterRoles = compiled.parameterRoles.filter { role -> role.parameters.all { it in available } })
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

    fun build(pipeline: PSD2LivePipeline, analysis: PipelineAnalysis, config: PipelineConfig,
              progress: ProgressListener, previousAtlas: PackedAtlas?): RigPreviewModel {
        val stack = RigModifierStack.fromJson(requireNotNull(config.rigEdits.modifierStack))
        require(config.rigEdits.swingEdits.isEmpty() && config.rigEdits.simEdits.isEmpty() && config.rigEdits.interiorEffects.isEmpty()) {
            "Legacy generators must be converted into explicit modifiers before enabling the stack"
        }
        val ordinary = ordinaryConfig(config)
        // Mesh-only is a construction input here, not an export gate on the ordinary authored content.
        val base = pipeline.buildOrdinaryPreview(analysis.source, ordinary.copy(meshOnly = true),
            ProgressListener { message, fraction -> progress.update(message, fraction * .8) }, previousAtlas)
        val input = compileOrdinary(base.analysis, base.atlas, base.rig, config)
        val source = RigModifierSource(base.analysis, base.atlas, config, base.rig.layerIdByDrawableId, base.rig.copiedDrawableIds)
        val result = RigModifierAlgorithms.evaluator(source).evaluate(input, stack) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Modifier evaluation cancelled")
            progress.update("Evaluating modifier stack", .85)
        }
        val rig = base.rig.withModifierMeshes(PuppetIr.toPuppet(result.rig), result.rig.textures.tileArt)
            .copy(modifierOutput = result.rig, modifierReport = result.report())
        val extra = RigModifierRasterAssets.layers(rig, config).filterNot { it.source.id.raw in base.atlas.placementByLayerId }
        val atlas = RigModifierRasterAssets.append(base.atlas, extra, config)
        val composed = if (extra.isEmpty()) base.analysis else base.analysis.copy(layers = base.analysis.layers + extra)
        progress.update("Compiling modifier result", .95)
        val bundle = pipeline.buildRuntimeBundle("psd2live-preview", composed, atlas, rig, config).first
        return base.copy(analysis = composed, atlas = atlas, rig = rig, config = config, runtimeBundle = bundle,
            modifierAuthorAtlas = base.atlas, modifierAuthorAnalysis = base.analysis)
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
