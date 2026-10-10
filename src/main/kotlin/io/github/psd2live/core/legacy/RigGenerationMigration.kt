package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import kotlinx.serialization.json.*
import io.github.psd2live.core.sim.*
import org.umamo.edit.withParameterDeleted
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.*

/** One ordered candidate for source, classification and generation-mode changes. */
internal object RigGenerationMigration {
    fun prepare(pipeline: PSD2LivePipeline, current: RigPreviewModel, requested: PipelineConfig,
                source: SourceArt = current.analysis.source, progress: ProgressListener): PipelineConfig = prepareOrdered(pipeline, current, requested, source,
        run {
            var last = 0.0
            ProgressListener { message, fraction ->
                last = maxOf(last, fraction.coerceIn(0.0, 1.0))
                progress.update(message, last)
            }
        })

    private fun prepareOrdered(pipeline: PSD2LivePipeline, current: RigPreviewModel, requested: PipelineConfig,
                               source: SourceArt, progress: ProgressListener): PipelineConfig {
        val frozenMesh = MaterializedMeshRebuild.freeze(current, requested)
        val frozen = frozenMesh.copy(rigEdits = RigGenerationBaseline.preserve(frozenMesh.rigEdits, current.config))
        val full = RigLayerDeletion.generationConfig(frozen)
        var preceding = if (full.rigEdits.importedCmo3 == null)
            pipeline.buildPreview(source, full, segment(progress, 0.0, 0.1)) else {
            progress.update("Preparing imported generation textures", 0.0)
            Cmo3ModelImport.paintingPreview(pipeline, source, full).also { progress.update("Prepared imported generation textures", 0.1) }
        }
        var activationOverlay = full.rigEdits
        val inputs = meshInput(source, requested).layers.associateBy { it.id.raw }
        for (drawable in preceding.rig.puppet.drawables.filter { it.mesh == null }) {
            val layerId = preceding.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
            val layer = inputs[layerId] ?: continue
            var hasArtwork = false
            for (index in 3 until layer.raster.rgba.size step 4) {
                if (index % 32768 == 3) progress.update("Inspecting guide artwork", 0.1)
                if ((layer.raster.rgba[index].toInt() and 255) > requested.alphaThreshold) { hasArtwork = true; break }
            }
            if (!hasArtwork) continue
            val art = object : SourceArt by source { override val layers = listOf(layer) }
            val neutralSemantic = CharacterAnalyzer.classify(layer, requested).semantic
            val neutral = pipeline.buildPreview(art, requested.copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
                generationSource = null, meshSource = null, parentOverrides = emptyMap(), deletedLayerIds = emptySet(),
                layerOverrides = requested.layerOverrides + (layerId to LayerClassificationOverride(LayerType.PRESET,
                    neutralSemantic.tag, neutralSemantic.side)),
                rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = mapOf(layerId to drawable.id.raw))))
            if (neutral.rig.puppet.drawables.none { it.id == drawable.id }) continue
            val placed = RasterMeshPlacement.underParents(neutral.rig, preceding.rig.puppet, { drawable.parentDeformerId }) {
                progress.update("Preparing guide mesh", 0.1)
            }
            val record = RigMeshActivation.encode(preceding.rig.puppet, placed, drawable.id)
            activationOverlay = activationOverlay.copy(authoringJournal = activationOverlay.authoringJournal + record)
        }
        val activated = full.copy(rigEdits = activationOverlay)
        if (activationOverlay != full.rigEdits) preceding = if (full.rigEdits.importedCmo3 == null)
            pipeline.buildPreview(source, activated, ProgressListener { message, _ -> progress.update(message, 0.1) }) else
            Cmo3ModelImport.paintingPreview(pipeline, source, activated)
        val desiredLayers = MouthLipLayers.prepare(CharacterAnalyzer.analyze(meshInput(source, requested),
            requested.copy(deletedLayerIds = emptySet())), requested.copy(deletedLayerIds = emptySet())).layers.associateBy { it.source.id.raw }
        val oldLayers = current.analysis.layers.associateBy { it.source.id.raw }
        val sourceChanged = source !== current.analysis.source && source != current.analysis.source
        val changed = preceding.rig.puppet.drawables.filter { drawable ->
            if (drawable.mesh == null) false else {
                val layerId = preceding.rig.layerIdByDrawableId[drawable.id.raw]
                val old = oldLayers[layerId]; val next = desiredLayers[layerId]
                sourceChanged || current.config.alphaThreshold != requested.alphaThreshold || current.config.meshUnits != requested.meshUnits ||
                    current.config.meshTrace != requested.meshTrace ||
                    (old != null && next != null && RigBuilder.meshSettings(old, current.config) != RigBuilder.meshSettings(next, requested)) ||
                    (next?.source is MouthLipLayer && (current.config.mouthThickness != requested.mouthThickness ||
                        current.config.mouthCurve != requested.mouthCurve || current.config.mouthShape != requested.mouthShape))
            }
        }.mapTo(LinkedHashSet()) { it.id.raw }
        val remeshed = MaterializedMeshRebuild.prepare(pipeline, preceding, activated, changed, segment(progress, 0.1, 0.4))
        // Simulation and swing materialize after the authoring journal. Their saved offsets remain
        // relative to the migrated base; taking their already materialized forms here would bake twice.
        val withoutLateForms = remeshed.copy(rigEdits = remeshed.rigEdits.copy(simEdits = emptyList(), swingEdits = emptyList()))
        val authored = if (full.rigEdits.importedCmo3 == null) pipeline.buildPreview(source, withoutLateForms, segment(progress, 0.4, 0.5))
            else Cmo3ModelImport.paintingPreview(pipeline, source, withoutLateForms).also { progress.update("Prepared authored generation bindings", 0.5) }
        val stable = remeshed.rigEdits.splitDrawableIds + authored.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
        fun generated(input: SourceArt, config: PipelineConfig, start: Double, end: Double): BuiltRig =
            generatedRig(pipeline, input, config, stable, segment(progress, start, end))
        val priorInput = current.config.generationSource?.let { RigGenerationSource.geometrySource(current.analysis.source, it, current.config.rigEdits) } ?: current.analysis.source
        val priorGenerated = canvasUvs(generated(priorInput, current.config, 0.5, 0.6))
        val savedPrevious = RigGenerationJournal.latest(current.config.rigEdits)
        val previous = if (savedPrevious == null) priorGenerated else {
            val bornIds = priorGenerated.drawables.map { it.id }.filterTo(HashSet()) { id -> savedPrevious.drawables.none { it.id == id } }
            if (bornIds.isEmpty()) savedPrevious else {
                val born = RigGenerationScaffold.rename(priorGenerated, savedPrevious,
                    remeshed.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.contentOrNull == RigGenerationJournal.OP } + 1)
                savedPrevious.copy(parameters = savedPrevious.parameters + born.parameters.filter { p -> savedPrevious.parameters.none { it.id == p.id } },
                    deformers = savedPrevious.deformers + born.deformers, drawables = savedPrevious.drawables + born.drawables.filter { it.id in bornIds })
            }
        }
        val desiredRig = generated(meshInput(source, requested), requested, 0.6, 0.7)
        var desired = canvasUvs(desiredRig)
        val activeIds = desired.drawables.mapTo(HashSet()) { it.id }
        val existing = authored.rig.puppet.parameters.associateBy { it.id }
        val deleted = current.config.rigEdits.deletedParameterIds.mapTo(HashSet(), ::ParameterId)
        for (record in current.config.rigEdits.authoringJournal) if (record["op"]?.jsonPrimitive?.contentOrNull == "structure") {
            for (element in record.getValue("edits").jsonArray) {
                val edit = element.jsonObject
                if (edit["kind"]?.jsonPrimitive?.contentOrNull != "parameter") continue
                val id = edit["id"]?.jsonPrimitive?.contentOrNull?.let(::ParameterId) ?: continue
                when (edit["action"]?.jsonPrimitive?.contentOrNull) {
                    "delete" -> deleted += id
                    "create" -> deleted -= id
                }
            }
        }
        val suppressed = (RigGenerationJournal.suppressedParameters(current.config.rigEdits) + deleted).filterTo(HashSet()) { it !in existing }
        val authoredParameters = RigGenerationJournal.authoredParameters(current.config.rigEdits).toMutableSet()
        current.config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == "structure" }.forEach { record ->
            record.getValue("edits").jsonArray.map { it.jsonObject }.filter { edit ->
                edit["kind"]?.jsonPrimitive?.contentOrNull == "parameter" && edit["action"]?.jsonPrimitive?.contentOrNull in setOf("create", "update")
            }.forEach { authoredParameters += ParameterId(it.getValue("id").jsonPrimitive.content) }
        }
        authoredParameters += existing.values.filter { actual -> previous.parameters.singleOrNull { it.id == actual.id } != actual }.map { it.id }
        current.config.rigEdits.importedCmo3?.let { baseline ->
            authoredParameters += Cmo3ModelImport.decode(baseline).puppet.parameters.map { it.id }
        }
        for (parameter in desired.parameters.filter { it.id in suppressed }) desired = desired.withParameterDeleted(parameter.id)
        desired = desired.copy(parameters = desired.parameters.map { generated ->
            existing[generated.id]?.takeIf { it.id in authoredParameters } ?: generated
        })
        // Inactive derived objects remain addressable by historical logs and later authored
        // edits. Retain their original automatic frames separately from the current scaffold.
        val inactiveIds = previous.drawables.map { it.id }.filterTo(HashSet()) { it !in activeIds }
        if (inactiveIds.isNotEmpty()) {
            val retired = RigGenerationScaffold.rename(previous, desired,
                remeshed.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.contentOrNull == RigGenerationJournal.OP } + 1)
            desired = desired.copy(parameters = desired.parameters + retired.parameters.filter { p -> desired.parameters.none { it.id == p.id } },
                deformers = desired.deformers + retired.deformers,
                drawables = desired.drawables + retired.drawables.filter { it.id in inactiveIds })
        }
        // Retired recipes are kept for restoration, but must also honor persistent parameter deletions.
        for (parameter in desired.parameters.filter { it.id in suppressed }) desired = desired.withParameterDeleted(parameter.id)
        val checkpoint = { progress.update("Migrating generation bindings", 0.75) }
        var model = authored.rig.puppet
        var overlay = remeshed.rigEdits
        val newIds = desired.drawables.map { it.id }.filterTo(HashSet()) { id -> id in activeIds && model.drawables.none { it.id == id } }
        val epoch = overlay.authoringJournal.count { it["op"]?.jsonPrimitive?.contentOrNull == RigGenerationJournal.OP } + 1
        val frames = RigGenerationFrames.prepare(model, previous, desired,
            RigGenerationFrames.previousIds(current.config.rigEdits), epoch, checkpoint)
        overlay = migrateFrameOffsets(overlay, frames.meshTransforms, checkpoint)
            .copy(authoringJournal = overlay.authoringJournal + frames.command)
        model = RigGenerationFrames.replay(model, frames.command)
        if (newIds.isNotEmpty()) {
            val named = frames.desired
            // Encode canvas texture coordinates using the new automatic rig, then resolve the
            // same layer against the current atlas on replay. No packed page address is frozen.
            for (id in newIds) {
                checkpoint()
                val rawRig = desiredRig.copy(puppet = named.copy(atlas = desiredRig.puppet.atlas, sources = desiredRig.puppet.sources,
                    drawables = named.drawables.filter { it.id in newIds }.map { d -> d.copy(mesh = desiredRig.puppet.drawables.single { it.id == d.id }.mesh,
                        atlasTileId = desiredRig.puppet.drawables.single { it.id == d.id }.atlasTileId,
                        texturePage = desiredRig.puppet.drawables.single { it.id == d.id }.texturePage) }))
                var creation = RasterMeshCreation.encode(rawRig, id)
                val layerId = desiredRig.layerIdByDrawableId.getValue(id.raw)
                val tile = model.atlas.tiles.singleOrNull { it.source?.layerKey == layerId }
                    ?: error("Generated artwork tile is missing: $layerId")
                val reference = requireNotNull(tile.source)
                creation = JsonObject(creation + buildJsonObject { put("source_id", reference.sourceId.raw); put("source", reference.layerKey) })
                // Newly generated masks may reference a mesh created later in the same transition.
                creation = JsonObject(creation + ("masks" to JsonArray(emptyList())))
                overlay = overlay.copy(authoringJournal = overlay.authoringJournal + creation)
                model = RasterMeshCreation.replay(model, creation)
            }
        }
        val visibilityOverrides = model.drawables.mapNotNull { d ->
            val layer = authored.rig.layerIdByDrawableId[d.id.raw] ?: desiredRig.layerIdByDrawableId[d.id.raw]
            requested.layerVisibility[layer]?.let { d.id.raw to it }
        }.toMap()
        val priorTextures = RigGenerationTextures.preserve(current.config.rigEdits, current.config, current.analysis.layers)
        val transition = RigGenerationJournal.encode(model, previous, desired, activeIds, RigGenerationJournal.retired(current.config.rigEdits),
            visibilityOverrides, suppressed, authoredParameters,
            RigGenerationTextures.preserve(current.config.rigEdits, requested, desiredLayers.values, priorTextures),
            checkpoint, frames.previousProjection, frames.desiredProjection)
        // Validate the full ordered record before the candidate can cross the runtime CAS boundary.
        RigGenerationJournal.replay(model, transition)
        progress.update("Prepared generation migration", 1.0)
        return remeshed.copy(rigEdits = overlay.copy(authoringJournal = overlay.authoringJournal + transition),
            deletedLayerIds = requested.deletedLayerIds)
    }

    /**
     * The automatic rig of [input] under [config] with no authored edits: what a generation transition compares the
     * authored model against. Without version 2 `art_primitive` records this is the plain generation, as it always
     * was. With them the build carries those records, so the base resolves the layer set the document shows - split
     * parts generated on their recorded meshes, the superseded originals only as stubs - and the result is the base's
     * [BuiltRig.resolvedPuppet] (stubs removed, parts in place, no authored layer), its layer map following suit.
     */
    internal fun generatedRig(pipeline: PSD2LivePipeline, input: SourceArt, config: PipelineConfig, stable: Map<String, String>,
                              progress: ProgressListener): BuiltRig {
        val resolved = ArtPrimitiveV2.resolve(config.rigEdits)
        val records = if (resolved.isEmpty()) emptyList() else config.rigEdits.authoringJournal.filter(ArtPrimitiveV2::isV2)
        val preview = pipeline.buildPreview(input,
            config.copy(generationSource = null, meshSource = null, parentOverrides = emptyMap(), deletedLayerIds = emptySet(),
                rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = stable, splitBaselineLayerIds = config.rigEdits.splitBaselineLayerIds,
                    calibrationLayerIds = config.rigEdits.calibrationLayerIds, skeleton = config.rigEdits.skeleton,
                    authoringJournal = records)), progress)
        if (resolved.isEmpty()) return preview.rig
        // Only the generated base: replaying the records would add their authored layers, which the transition
        // keeps as the residual of the authored model.
        val base = preview.baseRig
        val stubs = base.primitiveSkins.stubs.mapTo(HashSet()) { it.raw }
        return base.copy(puppet = base.resolvedPuppet(),
            layerIdByDrawableId = base.layerIdByDrawableId - stubs + resolved.parts.associate { it.drawableId.raw to it.layerId },
            sourceBoundsByDrawableId = base.sourceBoundsByDrawableId - stubs,
            pageByDrawableId = base.pageByDrawableId - stubs + resolved.parts.mapNotNull { part ->
                base.primitiveSkins.drawables[part.drawableId]?.let { part.drawableId.raw to it.texturePage }
            })
    }

    /**
     * [source] with each layer's saved mesh input in its place. The mesh input holds only the layers a mesh was
     * rebuilt from, beside a copy of the generation input taken when it was first saved - which can predate the
     * layers a partition or an import added since - so it never stands for the whole source.
     */
    internal fun meshInput(source: SourceArt, config: PipelineConfig): SourceArt {
        val saved = config.meshSource?.layers?.associateBy { it.id.raw } ?: return source
        return object : SourceArt by source {
            override val layers = source.layers.map { layer ->
                saved[layer.id.raw]?.let { input -> object : SourceLayer by input { override val order = layer.order } } ?: layer
            }
        }
    }

    private fun canvasUvs(rig: BuiltRig): PuppetModel = rig.puppet.copy(drawables = rig.puppet.drawables.map { drawable ->
        val mesh = drawable.mesh ?: return@map drawable
        drawable.copy(mesh = DrawableMesh(mesh.positions, RasterMeshJournal.TextureCoordinates(rig.puppet, drawable).toCanvas(mesh.uvs), mesh.indices))
    })

    private fun migrateFrameOffsets(overlay: RigEditOverlay, transforms: Map<String, (FloatArray) -> FloatArray>, checkpoint: () -> Unit): RigEditOverlay {
        if (transforms.isEmpty()) return overlay
        return overlay.copy(simEdits = overlay.simEdits.map { sim ->
            checkpoint()
            val bake = sim.bake ?: return@map sim
            fun axis(axis: SimBakedAxis): SimBakedAxis = SimBakedAxis(axis.parameter, axis.keys, axis.offsets.mapValues { (id, values) ->
                val transform = transforms[id] ?: return@mapValues values
                values.map { offset -> checkpoint(); transform(offset) }
            })
            sim.copy(bake = SimBakeResult.fromJson(bake.withGeometry(bake.vertexCounts,
                bake.statics.map(::axis), bake.modes.map { SimBakedMode(axis(it.axis), it.amplitude, it.energy) }).toJson()))
        })
    }

    private fun segment(progress: ProgressListener, start: Double, end: Double) = ProgressListener { message, fraction ->
        progress.update(message, start + (end - start) * fraction.coerceIn(0.0, 1.0))
    }
}
