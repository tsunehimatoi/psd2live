package io.github.psd2live.core

import io.github.psd2live.project.transform
import io.github.psd2live.core.sim.*
import org.umamo.edit.VertexSource
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.render.canvasToParentSpaceFor

/** Regenerate meshes without rewriting the geometry expected by earlier journal entries. */
internal object MaterializedMeshRebuild {
    fun freeze(current: RigPreviewModel, config: PipelineConfig): PipelineConfig {
        val membership = RigLayerDeletion.preserve(current, current.config.copy(rigEdits = config.rigEdits))
        return config.copy(rigEdits = MeshGenerationBaseline.preserve(membership, current.config),
            generationSource = config.generationSource ?: current.config.generationSource ?: current.analysis.source)
    }

    fun prepare(pipeline: PSD2LivePipeline, current: RigPreviewModel, config: PipelineConfig,
                changed: Set<String>, progress: ProgressListener): PipelineConfig {
        var overlay = MeshGenerationBaseline.preserve(config.rigEdits, current.config)
        var model = current.rig.puppet
        val source = current.analysis.source
        val meshSources = config.meshSource?.layers?.associateBy { it.id.raw }.orEmpty()
        val desiredLayers = if (RigGenerationBaseline.present(config.rigEdits))
            MouthLipLayers.prepare(CharacterAnalyzer.analyze(config.meshSource ?: source, config), config).layers.associateBy { it.source.id.raw }
            else emptyMap()
        for ((index, raw) in changed.withIndex()) {
            progress.update("Regenerating mesh ${index + 1}/${changed.size}", index.toDouble() / changed.size)
            val id = DrawableId(raw)
            val previous = model.drawables.singleOrNull { it.id == id } ?: continue
            if (previous.mesh == null) continue
            val layerId = current.rig.layerIdByDrawableId[raw] ?: continue
            // Meshed at canvas resolution like a generated layer: a source layer denser than its canvas rectangle is
            // read through its canvas view ([CanvasDensity]), never as if each texture pixel were a canvas unit.
            val layer = CanvasDensity.canvasLayer(meshSources[layerId] ?: desiredLayers[layerId]?.source ?: source.layers.singleOrNull { it.id.raw == layerId }
                ?: current.analysis.layers.singleOrNull { it.source.id.raw == layerId }?.source ?: continue)
            val input = object : SourceArt by source { override val layers = listOf(layer) }
            val classified = desiredLayers[layerId]?.copy(source = layer) ?: if (layerId in config.layerOverrides) CharacterAnalyzer.classify(layer, config) else
                current.analysis.layers.singleOrNull { it.source.id.raw == layerId }?.copy(source = layer)
                    ?: CharacterAnalyzer.classify(layer, config)
            val settings = RigBuilder.meshSettings(classified, config).first
            val neutralConfig = config.copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
                parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
                layerOverrides = config.layerOverrides + (layerId to LayerClassificationOverride(classified.semantic.type,
                    classified.semantic.tag, classified.semantic.side, classified.semantic.parameter, classified.semantic.switchId)),
                meshOverrides = mapOf(layerId to settings),
                rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = mapOf(layerId to raw)))
            val neutralAnalysis = current.analysis.copy(source = input, layers = listOf(classified), calibration = null,
                preview = PreviewRenderer.composite(input))
            val generatedProgress = ProgressListener { message, fraction -> progress.update(message, (index + fraction * 0.45) / changed.size) }
            val generated = if (layer is MouthLipLayer) {
                // A ribbon is generated from its owner's aperture and stroke, not from an
                // isolated alpha crop. Rebuild that real source context with stable mesh IDs.
                val stable = current.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
                pipeline.buildPreview(config.meshSource ?: source, config.copy(generatePhysics = false,
                    generationSource = null, meshSource = null, parentOverrides = emptyMap(), deletedLayerIds = emptySet(),
                    rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = stable,
                        calibrationLayerIds = config.rigEdits.calibrationLayerIds)), generatedProgress).rig
            } else pipeline.buildPreview(neutralAnalysis, neutralConfig, generatedProgress).rig
            // Clearing artwork retains its authored mesh; an empty silhouette has no replacement topology.
            if (generated.puppet.drawables.none { it.id == id }) continue
            val canvasGenerated = if (layer is MouthLipLayer) generated.copy(puppet = org.umamo.render.restMeshesToCanvasSpace(generated.puppet)) else generated
            val shownAs = (current.analysis.source.layers.firstOrNull { it.id.raw == layerId }
                ?: (layer as? MouthLipLayer)?.let { lip -> current.analysis.source.layers.firstOrNull { it.id.raw == lip.ownerId } })?.transform
                ?: io.github.psd2live.project.LayerTransform.IDENTITY
            val converted = RasterMeshPlacement.underParents(canvasGenerated, model, { previous.parentDeformerId }, { shownAs }) {
                progress.update("Preparing parent-space mesh", (index + 0.5) / changed.size)
            }
            val replacement = requireNotNull(converted.puppet.drawables.single { it.id == id }.mesh)
            val checkpoint = { progress.update("Migrating mesh bindings", (index + 0.75) / changed.size) }
            val imported = config.rigEdits.importedCmo3?.let { Cmo3ModelImport.decode(it).puppet.drawables.singleOrNull { it.id == id } }
            val previousPositions = imported?.takeIf { it.parentDeformerId == previous.parentDeformerId &&
                it.mesh?.positions?.contentEquals(requireNotNull(previous.mesh).positions) == true }
                ?.let { canvasToParentSpaceFor(model)(id, requireNotNull(previous.mesh).positions) }
            val command = RasterMeshJournal.encode(model, id, replacement, converted.puppet,
                generated.sourceBoundsByDrawableId[raw], checkpoint, referenceModel = model, previousPositions = previousPositions)
            if (RasterMeshJournal.isNoOp(model, command)) continue
            val mesh = requireNotNull(previous.mesh)
            val plan = RasterMeshJournal.prepare(previousPositions?.let { DrawableMesh(it, mesh.uvs, mesh.indices) } ?: mesh, replacement, checkpoint)
            overlay = migrateSimulations(overlay, raw, mesh.vertexCount, plan.sources, checkpoint)
            overlay = overlay.copy(authoringJournal = overlay.authoringJournal + command)
            model = RasterMeshJournal.replay(model, command)
        }
        progress.update("Replaying regenerated meshes", 1.0)
        return config.copy(rigEdits = overlay)
    }

    private fun migrateSimulations(overlay: RigEditOverlay, id: String, count: Int,
                                   sources: List<VertexSource>, checkpoint: () -> Unit): RigEditOverlay = overlay.copy(simEdits = overlay.simEdits.map { sim ->
        checkpoint()
        if (id !in sim.targets || sim.bake == null) sim else {
            val bake = sim.bake
            require(bake.vertexCounts[id] == count) { "Simulation bake has a stale mesh topology" }
            fun migrate(axis: SimBakedAxis): SimBakedAxis {
                val previous = axis.offsets[id] ?: return axis
                require(previous.all { it.size == count * 2 }) { "Simulation bake has a stale mesh topology" }
                return SimBakedAxis(axis.parameter, axis.keys, axis.offsets + (id to previous.map { values ->
                    checkpoint()
                    FloatArray(sources.size * 2) { index ->
                        val axisIndex = index % 2
                        when (val source = sources[index / 2]) {
                            is VertexSource.FromOld -> values[source.oldIndex * 2 + axisIndex]
                            is VertexSource.BarycentricOf -> values[source.oldA * 2 + axisIndex] * source.wa +
                                values[source.oldB * 2 + axisIndex] * source.wb + values[source.oldC * 2 + axisIndex] * source.wc
                            else -> error("Unsupported mesh vertex source")
                        }
                    }
                }))
            }
            sim.copy(bake = SimBakeResult.fromJson(bake.withGeometry(bake.vertexCounts + (id to sources.size), bake.statics.map(::migrate),
                bake.modes.map { SimBakedMode(migrate(it.axis), it.amplitude, it.energy) }).toJson()))
        }
    })
}
