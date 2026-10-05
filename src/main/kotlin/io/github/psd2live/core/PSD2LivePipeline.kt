package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.art.SourceArt
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.FileReferences
import org.umamo.format.moc3.json.Model3Group
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.json.Model3Motion
import org.umamo.format.psd.PsdReader
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3FileFormatVersion
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.interop.mocVersion
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.DrawableId
import org.umamo.edit.withDrawablesDeleted
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class PSD2LivePipeline {
	private val meshCache = PreviewMeshCache()
	fun inspect(psd: Path, config: PipelineConfig = PipelineConfig()): PipelineAnalysis {
		require(Files.isRegularFile(psd)) { tr("error.psdMissing", psd) }
		val bytes = Files.readAllBytes(psd)
		require(PsdReader.matches(bytes)) { tr("error.invalidPsd", psd) }
		return RigGenerationSource.analyze(PsdReader.read(bytes), config)
	}

	fun buildPreview(psd: Path, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(inspect(psd, config), config)

	fun buildPreview(
		source: SourceArt,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel = buildPreview(if (config.rigEdits.importedCmo3 != null) Cmo3ModelImport.analysis(source, config)
		else RigGenerationSource.analyze(source, config), config, progress)

	fun buildPreview(
		analysis: PipelineAnalysis,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel {
        if (RigLayerDeletion.deferred(config)) {
            return RigLayerDeletion.preview(buildPreview(analysis.source, RigLayerDeletion.generationConfig(config), progress), config)
        }
        if (config.rigEdits.importedCmo3 != null) {
            val importedAnalysis = Cmo3ModelImport.analysis(analysis.source, config)
            val (atlas, baseRig) = Cmo3ModelImport.baseRig(analysis.source, config)
            val rig = baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
            val bundle = buildRuntimeBundle("psd2live-preview", importedAnalysis, atlas, rig, config).first
            return RigPreviewModel(importedAnalysis, atlas, rig, config, bundle, baseRig)
        }
        val (effectiveAnalysis, atlas, baseRig) = generatedBase(analysis, config, progress)
		val rig = baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
		val runtimeBundle = buildRuntimeBundle("psd2live-preview", effectiveAnalysis, atlas, rig, config).first
		return RigPreviewModel(effectiveAnalysis, atlas, rig, config, runtimeBundle, baseRig = baseRig)
	}

	private data class GeneratedBase(val analysis: PipelineAnalysis, val atlas: PackedAtlas, val rig: BuiltRig)

	/** Preview, path normalization and export must use the same saved generation input. */
	private fun generatedBase(input: PipelineAnalysis, config: PipelineConfig, progress: ProgressListener): GeneratedBase {
		val baselineConfig = RigGenerationBaseline.restore(MeshGenerationBaseline.restore(config))
		val analyses = RigGenerationSource.prepare(input, baselineConfig, config)
		val createdLayers = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
			.mapTo(HashSet()) { it.getValue("layer_id").jsonPrimitive.content }
		createdLayers += SourcePartitionJournal.commands(config.rigEdits).flatMap(SourcePartitionJournal::pieces)
			.map { it.getValue("layer_id").jsonPrimitive.content }
		val generationConfig = baselineConfig.copy(parentOverrides = config.parentOverrides - createdLayers)
		val atlas = AtlasPacker.pack(analyses.textures.layers, config.atlasSize, config.texturePadding, config.textureUpscale, progress)
		if (config.generationSource == null) return GeneratedBase(analyses.textures, atlas,
			withoutCreatedMeshes(RigBuilder.build(analyses.geometry, atlas, generationConfig, meshCache), config))
		val geometryAtlas = AtlasPacker.pack(analyses.geometry.layers, config.atlasSize, config.texturePadding, config.textureUpscale, progress)
		val generated = RigBuilder.build(analyses.geometry, geometryAtlas, generationConfig, meshCache)
		return GeneratedBase(analyses.textures, atlas,
			withoutCreatedMeshes(RigGenerationSource.repack(generated, analyses.geometry, geometryAtlas, analyses.textures, atlas), config))
	}

    /** Keep the original analysis/parent frames, but let journal creations own their mesh IDs. */
    private fun withoutCreatedMeshes(rig: BuiltRig, config: PipelineConfig): BuiltRig {
        val ids = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
            .mapTo(HashSet()) { it.getValue("id").jsonPrimitive.content }
        ids += SourcePartitionJournal.commands(config.rigEdits).flatMap(SourcePartitionJournal::pieces)
            .map { it.getValue("id").jsonPrimitive.content }
        if (ids.isEmpty()) return rig
        return rig.copy(puppet = rig.puppet.withDrawablesDeleted(ids.mapTo(HashSet()) { DrawableId(it) }),
            layerIdByDrawableId = rig.layerIdByDrawableId - ids, sourceBoundsByDrawableId = rig.sourceBoundsByDrawableId - ids,
            pageByDrawableId = rig.pageByDrawableId - ids)
    }

	/** A source partition keeps the existing Warp lattices and their coordinate frames verbatim. */
	internal fun buildPreviewAfterLayerSplit(
		current: RigPreviewModel,
		source: SourceArt,
		config: PipelineConfig,
	): RigPreviewModel {
		val analysis = MouthLipLayers.prepare(CharacterAnalyzer.analyze(source, config), config)
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding, config.textureUpscale)
		val existingIds = current.rig.layerIdByDrawableId.map { (drawableId, layerId) -> layerId to DrawableId(drawableId) }.toMap() +
			config.rigEdits.splitDrawableIds.mapValues { DrawableId(it.value) }
		val ids = RigBuilder.assignSplitDrawableIds(analysis, existingIds)
		val committedConfig = config.copy(rigEdits = config.rigEdits.copy(
			splitDrawableIds = ids.mapValues { it.value.raw },
		))
		val generated = RigBuilder.buildPreservingDeformers(
			analysis, atlas, config, meshCache, current.analysis, current.config, ids,
		)
		val baseRig = generated.copy(puppet = generated.puppet.copy(
			deformers = current.baseRig.puppet.deformers,
			parameters = (generated.puppet.parameters + current.baseRig.puppet.parameters).distinctBy { it.id },
		))
		val replayed = baseRig.withRigEdits(committedConfig.rigEdits, committedConfig.layerVisibility, committedConfig.drawOrderOverrides)
		val rig = replayed.copy(puppet = replayed.puppet.copy(
			deformers = current.rig.puppet.deformers,
			parameters = (replayed.puppet.parameters + current.rig.puppet.parameters).distinctBy { it.id },
		))
		val bundle = buildRuntimeBundle("psd2live-preview", analysis, atlas, rig, committedConfig).first
		return RigPreviewModel(analysis, atlas, rig, committedConfig, bundle, baseRig)
	}

	/** Hierarchy-only edits retain textures but rebuild all parent-space geometry and keyforms. */
	fun rebuildPreview(
		current: RigPreviewModel,
		config: PipelineConfig,
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel {
        if (RigGenerationMigration.changed(current, config)) {
            val prepared = RigGenerationMigration.prepare(this, current, config,
                progress = ProgressListener { message, fraction -> progress.update(message, fraction * 0.8) })
            return buildPreview(current.analysis.source, prepared,
                ProgressListener { message, fraction -> progress.update(message, 0.8 + fraction * 0.2) })
        }
        if (meshControlsChanged(current.config, config) && current.config.layerOverrides == config.layerOverrides &&
            current.config.meshOnly == config.meshOnly && current.config.mouthOutlineEnabled == config.mouthOutlineEnabled) {
            val frozen = MaterializedMeshRebuild.freeze(current, config)
            val fullConfig = RigLayerDeletion.generationConfig(frozen)
            val preceding = if (config.rigEdits.importedCmo3 == null)
                buildPreview(current.analysis.source, fullConfig, ProgressListener { message, fraction -> progress.update(message, fraction * 0.15) })
            else {
                progress.update("Preparing imported mesh textures", 0.0)
                Cmo3ModelImport.paintingPreview(this, current.analysis.source, fullConfig).also {
                    progress.update("Prepared imported mesh textures", 0.15)
                }
            }
            val changed = meshSettingsChangedDrawableIds(preceding.copy(config = current.config), fullConfig)
            val prepared = MaterializedMeshRebuild.prepare(this, preceding, fullConfig, changed,
                ProgressListener { message, fraction -> progress.update(message, 0.15 + fraction * 0.65) })
                .copy(deletedLayerIds = config.deletedLayerIds)
            return buildPreview(current.analysis.source, prepared,
                ProgressListener { message, fraction -> progress.update(message, 0.8 + fraction * 0.2) })
        }
        if (RigLayerDeletion.deferred(config)) {
            val full = buildPreview(current.analysis.source, RigLayerDeletion.generationConfig(current.config),
                ProgressListener { message, fraction -> progress.update(message, fraction * 0.15) })
            val rebuilt = rebuildPreview(full, RigLayerDeletion.generationConfig(config),
                ProgressListener { message, fraction -> progress.update(message, 0.15 + fraction * 0.85) })
            return RigLayerDeletion.preview(rebuilt, config.copy(rigEdits = rebuilt.config.rigEdits))
        }
        if (config.rigEdits.importedCmo3 != null) return buildPreview(current.analysis, config, progress)
		if (MeshGenerationBaseline.present(config.rigEdits)) return buildPreview(current.analysis.source, config, progress)
		if (current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits, drawOrderOverrides = config.drawOrderOverrides,
				hairSimulationFront = config.hairSimulationFront, hairSimulationBack = config.hairSimulationBack) == config) {
			val baseRig = if (config.generationSource == null) RigBuilder.build(current.analysis, current.atlas, config, meshCache)
				else generatedBase(current.analysis, config, progress).rig
			val rig = baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
			val bundle = buildRuntimeBundle("psd2live-preview", current.analysis, current.atlas, rig, config).first
			return current.copy(rig = rig, config = config, runtimeBundle = bundle, baseRig = baseRig)
		}
		val base = current.analysis.copy(layers = current.analysis.layers.filter { it.source !is MouthLipLayer })
		return buildPreviewPreservingPaths(current, base, config, progress)
	}

	/**
	 * A mesh-setting or source rebuild can change triangle indices. Saved deform paths store barycentric
	 * bindings, so replaying their old indices against the replacement mesh either moves the path or
	 * rejects the entire preview. Build once without path journal entries, then rebind the saved path
	 * positions to the new meshes before replaying them.
	 */
	private fun buildPreviewPreservingPaths(
		current: RigPreviewModel,
		analysis: PipelineAnalysis,
		config: PipelineConfig,
		progress: ProgressListener,
	): RigPreviewModel {
		val journal = config.rigEdits.authoringJournal
		val pathPuts = journal.filter { it["op"]?.jsonPrimitive?.contentOrNull == "path_put" }
		val pathDeletes = journal.filter { it["op"]?.jsonPrimitive?.contentOrNull == "path_delete" }
		val rebuiltMeshIds = meshSettingsChangedDrawableIds(current, config)
		if (pathPuts.isEmpty() && pathDeletes.isEmpty() && rebuiltMeshIds.isEmpty()) {
			return buildPreview(analysis, config, progress)
		}

		val retainedJournal = journal.filterNot {
			it["op"]?.jsonPrimitive?.contentOrNull == "path_put" ||
				it["op"]?.jsonPrimitive?.contentOrNull == "path_delete"
		}
		var retainedEdits = config.rigEdits.copy(authoringJournal = retainedJournal)
		// Meshes born after the original generation input are materialized in the journal. Regenerate
		// their creation geometry too, before retiring edits tied to the previous vertex inventory.
		val creations = retainedJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP &&
			it["id"]?.jsonPrimitive?.contentOrNull in rebuiltMeshIds }
		val refreshedCreations = if (creations.isEmpty()) emptyMap() else {
			val meshInput = config.meshSource ?: analysis.source
			val customCreations = retainedJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }.filter { command -> command["parent"]?.jsonPrimitive?.contentOrNull?.let { parent ->
				current.baseRig.puppet.deformers.none { it.id.raw == parent }
			} == true }
			val custom = customCreations.filter { it["id"]?.jsonPrimitive?.contentOrNull in rebuiltMeshIds }
			val customLayers = custom.mapTo(HashSet()) { it.getValue("layer_id").jsonPrimitive.content }
			val meshConfig = config.copy(parentOverrides = config.parentOverrides - customCreations.map { it.getValue("layer_id").jsonPrimitive.content })
			val meshAnalysis = MouthLipLayers.prepare(CharacterAnalyzer.analyze(meshInput, meshConfig), meshConfig)
			val meshAtlas = AtlasPacker.pack(meshAnalysis.layers, config.atlasSize, config.texturePadding, config.textureUpscale, progress)
			val stableIds = RigBuilder.assignSplitDrawableIds(meshAnalysis, current.rig.layerIdByDrawableId
				.map { (id, layer) -> layer to DrawableId(id) }.toMap())
			val originalFrames = RigGenerationSource.prepare(current.analysis, current.config).geometry
			val regenerated = RigBuilder.buildPreservingDeformers(meshAnalysis, meshAtlas, meshConfig, meshCache,
				originalFrames, current.config, stableIds)
			val customRig = if (custom.isEmpty()) null else {
				val source = object : SourceArt {
					override val widthPx = meshInput.widthPx
					override val heightPx = meshInput.heightPx
					override val groups = meshInput.groups
					override val layers = meshInput.layers.filter { it.id.raw in customLayers }
				}
				val neutralConfig = config.copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
					parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
					rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = stableIds.filterKeys { it in customLayers }.mapValues { it.value.raw }))
				val neutral = buildPreview(source, neutralConfig, progress).rig
				val parents = custom.associate { it.getValue("id").jsonPrimitive.content to org.umamo.runtime.model.DeformerId(it.getValue("parent").jsonPrimitive.content) }
				RasterMeshPlacement.underParents(neutral, current.rig.puppet, { parents[it.raw] }) { progress.update("Preparing parent-space mesh", 0.5) }
			}
			creations.associate { command ->
				val id = DrawableId(command.getValue("id").jsonPrimitive.content)
				val replacementRig = customRig?.takeIf { rig -> rig.puppet.drawables.any { it.id == id } } ?: regenerated
				require(replacementRig.puppet.drawables.any { it.id == id }) { "Mesh settings cannot remove a created mesh" }
				val replacement = RasterMeshCreation.encode(replacementRig, id)
				val geometryFields = setOf("source_bounds", "positions", "triangles", "canvas_uvs", "neutral_bounds", "geometry", "channels", "parameters", "paths")
				id.raw to kotlinx.serialization.json.JsonObject(command + replacement.filterKeys { it in geometryFields })
			}
		}
		retainedEdits = retainedEdits.copy(authoringJournal = retainedEdits.authoringJournal.map { command ->
			if (command["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP)
				refreshedCreations[command["id"]?.jsonPrimitive?.contentOrNull] ?: command else command
		})
		val (effectiveAnalysis, atlas, baseRig) = generatedBase(analysis, config, progress)
		for (drawableId in rebuiltMeshIds) {
			val previousVertexCount = current.baseRig.puppet.drawables
				.firstOrNull { it.id.raw == drawableId }?.mesh?.vertexCount
				?: current.rig.puppet.drawables.firstOrNull { it.id.raw == drawableId }?.mesh?.vertexCount
				?: 0
			val vertexCount = baseRig.puppet.drawables
				.firstOrNull { it.id.raw == drawableId }?.mesh?.vertexCount
				?: refreshedCreations[drawableId]?.get("positions")?.let { it.jsonArray.size / 2 } ?: continue
			retainedEdits = MeshRebuildEdits.reset(
				retainedEdits,
				drawableId,
				previousVertexCount,
				vertexCount,
				meshSettingsChanged = true,
			)
		}
		val retainedRig = baseRig.withRigEdits(retainedEdits, config.layerVisibility, config.drawOrderOverrides)
		val replacementBasePaths = retainedRig.puppet.deformPaths.mapTo(HashSet()) { it.id }
		val deleteCommands = pathDeletes
			.distinctBy { it["id"]?.jsonPrimitive?.content }
			.filter { command ->
				command["id"]?.jsonPrimitive?.content?.let(replacementBasePaths::contains) == true
			}

		val authoredPathIds = pathPuts.mapNotNullTo(HashSet<String>()) { it["id"]?.jsonPrimitive?.content }
		val replacementDrawables = retainedRig.puppet.drawables.associateBy { it.id.raw }
		val previousDrawables = current.rig.puppet.drawables.associateBy { it.id.raw }
		val putCommands = current.rig.puppet.deformPaths
			.filter { it.id in authoredPathIds }
			.mapNotNull { path ->
				val oldMesh = previousDrawables[path.drawableId.raw]?.mesh ?: return@mapNotNull null
				val newMesh = replacementDrawables[path.drawableId.raw]?.mesh ?: return@mapNotNull null
				DeformPathJournal.encode(DeformPathJournal.rebind(path, oldMesh, newMesh))
			}

		val rebasedEdits = rebuiltMeshIds.fold(retainedEdits.copy(
			authoringJournal = retainedEdits.authoringJournal + deleteCommands + putCommands,
		)) { edits, drawableId ->
			val groups = VertexGroupJournal.rebuiltGroups(current.rig.puppet, retainedRig.puppet, drawableId)
			if (groups.isEmpty()) edits else VertexGroupJournal.replaceMeshGroups(edits, drawableId, groups)
		}
		val rebasedConfig = config.copy(rigEdits = rebasedEdits)
		val rig = baseRig.withRigEdits(rebasedEdits, config.layerVisibility, config.drawOrderOverrides)
		val runtimeBundle = buildRuntimeBundle(
			"psd2live-preview",
			effectiveAnalysis,
			atlas,
			rig,
			rebasedConfig,
		).first
		return RigPreviewModel(effectiveAnalysis, atlas, rig, rebasedConfig, runtimeBundle, baseRig)
	}

	private fun globalMeshControlsChanged(before: PipelineConfig, config: PipelineConfig) =
		before.meshSpacing != config.meshSpacing || before.meshOuterMargin != config.meshOuterMargin ||
		before.meshEdgeMode != config.meshEdgeMode || before.meshEdgeWidth != config.meshEdgeWidth ||
		before.meshMaxEdgeDistance != config.meshMaxEdgeDistance || before.meshInteriorDensity != config.meshInteriorDensity ||
		before.meshFillAlgorithm != config.meshFillAlgorithm || before.meshSuppressBoundaryDiagonals != config.meshSuppressBoundaryDiagonals ||
		before.meshFillParameters != config.meshFillParameters || before.meshUnits != config.meshUnits ||
		before.alphaThreshold != config.alphaThreshold

	private fun meshControlsChanged(before: PipelineConfig, config: PipelineConfig) =
		globalMeshControlsChanged(before, config) || before.meshOverrides != config.meshOverrides

	internal fun meshSettingsChangedDrawableIds(current: RigPreviewModel, config: PipelineConfig): Set<String> {
		val globalSettingsChanged = globalMeshControlsChanged(current.config, config) ||
			current.config.meshOnly != config.meshOnly ||
			current.config.mouthOutlineEnabled != config.mouthOutlineEnabled ||
			current.config.layerOverrides != config.layerOverrides
		val layersByMesh = current.rig.puppet.drawables.filter { it.mesh != null }.associate {
			it.id.raw to (current.rig.layerIdByDrawableId[it.id.raw] ?: it.id.raw)
		}.toMutableMap()
		if (RigLayerDeletion.deferred(current.config)) {
			// Deleted meshes still participate in regeneration and must retain replayable topology.
			current.baseRig.puppet.drawables.filter { it.mesh != null }.forEach {
				layersByMesh[it.id.raw] = current.baseRig.layerIdByDrawableId[it.id.raw] ?: it.id.raw
			}
			current.config.rigEdits.authoringJournal.filter {
				it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP
			}.forEach { layersByMesh[it.getValue("id").jsonPrimitive.content] = it.getValue("layer_id").jsonPrimitive.content }
		}
		return layersByMesh.filterValues { layerId ->
			globalSettingsChanged || current.config.meshOverrides[layerId] != config.meshOverrides[layerId]
		}.keys
	}

	/** Fast incremental update for physics, motions and sidecars without re-analyzing or re-packing. */
	fun updateRuntimeBundle(
		current: RigPreviewModel,
		config: PipelineConfig,
		baseName: String = "psd2live-preview",
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel {
		if (config.textureUpscale != current.config.textureUpscale) {
			return buildPreview(current.analysis, config, progress)
		}
		val (runtimeBundle, _) = buildRuntimeBundle(baseName, current.analysis, current.atlas, current.rig, config)
		return current.copy(config = config, runtimeBundle = runtimeBundle, baseRig = current.baseRig)
	}

	/** Fast incremental update for rig and keyform edits replayed onto the cached base rig. */
	fun updateRigEdits(
		current: RigPreviewModel,
		config: PipelineConfig,
		baseName: String = "psd2live-preview",
	): RigPreviewModel {
		if (RigLayerDeletion.deferred(config)) return buildPreview(current.analysis.source, config)
		val rig = current.baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
		val (runtimeBundle, _) = buildRuntimeBundle(baseName, current.analysis, current.atlas, rig, config)
		return current.copy(
			rig = rig,
			config = config,
			runtimeBundle = runtimeBundle,
			baseRig = current.baseRig,
		)
	}

	fun canFastUpdateRig(
		current: RigPreviewModel?,
		source: SourceArt,
		config: PipelineConfig,
	): Boolean {
		if (current == null) return false
		if (current.config.rigEdits.importedCmo3 != config.rigEdits.importedCmo3) return false
		if (current.analysis.source !== source && current.analysis.source != source) return false
		if (current.config.rigEdits.skeleton != config.rigEdits.skeleton) return false
		return current.config.copy(rigEdits = config.rigEdits) == config
	}

	fun run(
		psd: Path,
		outputDirectory: Path,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): PipelineResult {
		progress.update(tr("progress.readPsd"), 0.04)
		val analysis = inspect(psd, config)
		return exportAnalysis(
			inputAnalysis = analysis,
			baseName = safeBaseName(psd.fileName.toString().substringBeforeLast('.')),
			outputDirectory = outputDirectory,
			config = config,
			progress = progress,
		)
	}

	/** Export the current authoritative source, including Agent-created layers, without rereading the PSD. */
	fun run(
		source: SourceArt,
		sourceName: String,
		outputDirectory: Path,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): PipelineResult {
		progress.update(tr("progress.readPsd"), 0.04)
		val analysis = if (config.rigEdits.importedCmo3 != null) Cmo3ModelImport.analysis(source, config)
			else RigGenerationSource.analyze(source, config)
		return exportAnalysis(
			inputAnalysis = analysis,
			baseName = safeBaseName(sourceName.substringBeforeLast('.')),
			outputDirectory = outputDirectory,
			config = config,
			progress = progress,
		)
	}

	private fun exportAnalysis(
		inputAnalysis: PipelineAnalysis,
		baseName: String,
		outputDirectory: Path,
		config: PipelineConfig,
		progress: ProgressListener,
	): PipelineResult {
		val imported = config.rigEdits.importedCmo3 != null
		progress.update(tr("progress.classify"), 0.18)
		val replayConfig = RigLayerDeletion.generationConfig(config)
		val replayAnalysis = if (replayConfig == config) inputAnalysis else RigGenerationSource.analyze(inputAnalysis.source, replayConfig)
		val prepared = if (imported) {
			val (atlas, rig) = Cmo3ModelImport.baseRig(inputAnalysis.source, replayConfig)
			GeneratedBase(replayAnalysis, atlas, rig)
		} else generatedBase(replayAnalysis, replayConfig, progress)
		val (_, atlas, baseRig) = prepared
		val analysis = RigLayerDeletion.analysis(prepared.analysis, config)
		val rig = RigLayerDeletion.rig(baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides), prepared.analysis, config)
        val checks = mutableListOf(QualityCheckResult("generation", "Source classification and mesh generation", analysis.qualityFindings + rig.qualityFindings),
            ModelIntegrityCheck.inspect(ModelIntegrityInput("model.generated", rig.puppet, rig.sourceBoundsByDrawableId, tr("validation.generated"))))
        fun quality() = QualityInspection.combine(QualityFence.EXPORT_PUBLICATION, checks)
        fun fence() { val report = quality(); report.fence.requireAccepted(report) { report.toJson() } }
        fence()
		progress.update(tr("progress.keyforms"), 0.58)
		// CMO3's editable base mesh is canvas-space. The keyform absolutes remain in parent space;
		// Umamo's conversion preserves that mixed-space invariant exactly.
		// Evaluating the mouth at MOUTH_OPEN=1.0f keeps the base mesh in its initial open state matching
		// the authored PSD layer artwork and atlas UV coordinates, avoiding singular affine transforms in CMO3.
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet, if (imported) emptyMap() else mapOf(StandardParameters.MOUTH_OPEN to 1.0f))
		val outputRoot = outputDirectory.toAbsolutePath().normalize()
		Files.createDirectories(outputRoot)
		val pendingFiles = mutableListOf<Pair<String, ByteArray>>()

		val (runtimeBundle, runtimeReport) = buildRuntimeBundle(baseName, analysis, atlas, rig, config)

		if (config.exportMoc3) {
			checks += ExportConversionCheck.inspect(ExportConversionInput("MOC3", runtimeReport.notices))
			val mocBytes = runtimeBundle.assets.first { it.path.endsWith(".moc3") }.bytes
			val reimported = Moc3Import.fromMocDocument(Moc3.read(mocBytes), null)
			checks += ExportIdentityCheck.inspect(ExportIdentityInput("MOC3", exportPuppet, reimported, runtimeReport.notices))
            checks += ModelIntegrityCheck.inspect(ModelIntegrityInput("model.moc3", reimported, rig.sourceBoundsByDrawableId, tr("validation.moc3Readback")))
            fence()
            for (file in runtimeBundle.assets) pendingFiles += file.path to file.bytes

		}
		progress.update(tr("progress.exportMoc3"), 0.77)

		if (config.exportCmo3) {
			val pages = atlas.pages.map { page ->
				Cmo3Conversion.AtlasPage(page.png, page.image.width, page.image.height)
			}
			val tileRasters = PuppetSourceAtlas.rastersByTile(analysis)
			val converted = Cmo3Conversion.freshCmo3(
				puppet = exportPuppet,
				pages = pages,
				pageIndexByDrawableId = rig.pageByDrawableId,
				modelName = baseName,
				nowMillis = Instant.now().toEpochMilli(),
				obfuscateKey = 0x42,
				tileRasters = { tileId -> tileRasters[tileId] },
			)
			val physics = PhysicsCatalog.active(analysis, config, rig.puppet.parameters.mapTo(HashSet()) { it.id.raw })
			if (physics.isNotEmpty()) Cmo3PhysicsInjector.inject(converted.model.root as CModelSource, physics, config.rigEdits.physicsFps)
			BezierWarp.configureEditor(converted.model.root as CModelSource, config.rigEdits)
			val bytes = Cmo3.write(converted.model)
			checks += ExportConversionCheck.inspect(ExportConversionInput("CMO3", converted.report.notices))
			val source = Cmo3.read(bytes).root as? CModelSource ?: error(tr("error.cmo3Root"))
			val reimported = Cmo3Import.fromModelSource(source)
			checks += ExportIdentityCheck.inspect(ExportIdentityInput("CMO3", converted.puppet, reimported, converted.report.notices))
            checks += ModelIntegrityCheck.inspect(ModelIntegrityInput("model.cmo3", reimported, rig.sourceBoundsByDrawableId, tr("validation.cmo3Readback")))
            fence()
            pendingFiles += "$baseName.cmo3" to bytes

		}
		progress.update(tr("progress.exportCmo3"), 0.91)

        val qualityReport = quality()
        val warnings = qualityReport.findings.filter { it.rule.severity != QualitySeverity.INFO }.map { it.message }
        if (config.exportJson) {
			val report = JsonObject(Json.parseToJsonElement(projectReport(baseName, analysis, rig, atlas, config, warnings)).jsonObject + ("quality" to qualityReport.toJson())).toString()
			Json.parseToJsonElement(report)
			pendingFiles += "$baseName.psd2live.json" to report.encodeToByteArray()
		}
        fence()
        val files = pendingFiles.map { (path, bytes) -> writeContained(outputRoot, path, bytes) }
		progress.update(tr("progress.validated"), 1.0)
		return PipelineResult(analysis, files, warnings, RigPreviewModel(analysis, atlas, rig, config, runtimeBundle, baseRig = baseRig), qualityReport)
	}

	internal fun buildRuntimeBundle(
		baseName: String,
		analysis: PipelineAnalysis,
		atlas: PackedAtlas,
		rig: BuiltRig,
		config: PipelineConfig,
	): Pair<CubismRuntimeBundle, org.umamo.interop.ExportReport> {
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet, if (config.rigEdits.importedCmo3 != null) emptyMap() else mapOf(StandardParameters.MOUTH_OPEN to 1.0f))
		val parameterIds = rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }
		val textureFolder = "$baseName.${atlas.pages.firstOrNull()?.image?.width ?: config.atlasSize}"
		val pages = atlas.pages.mapIndexed { index, page ->
			Moc3Sidecars.AtlasPage("$textureFolder/texture_${index.toString().padStart(2, '0')}.png", page.png)
		}
		val physicsGroups = PhysicsCatalog.active(analysis, config, parameterIds)
		val physics = Physics3Json.write(physicsGroups, config.rigEdits.physicsFps)?.let(CubismJson::normalize)

		val motions = buildList<Pair<String, Pair<String, String>>> {
			if (config.exportMotions && !config.meshOnly) {
				val clips = MotionClips.reconcileParameters(config.rigEdits.motionClips, rig.puppet.parameters)
				fun add(group: String, file: String, motion: String?) {
					motion ?: return
					val json = CubismJson.normalize(motion).also { Json.parseToJsonElement(it) }
					add(group to ("$baseName.$file.motion3.json" to json))
				}
				// An edited generated motion exports its clip in place of the generated one; a deleted one is left out.
				val skeleton = config.rigEdits.skeleton
				fun builtin(group: String, name: String, exclude: Set<String> = emptySet()) {
					val settings = config.rigEdits.motionPresets[name] ?: MotionPresetSettings()
					if (settings.deleted) return
					val override = MotionClips.overrideOf(clips, name)
					add(group, name.replaceFirstChar(Char::lowercase), if (override != null) MotionGenerator.clip(override, parameterIds) else {
						val tracks = MotionPresets.tracks(name, skeleton, settings, exclude)
						MotionGenerator.tracks(tracks, parameterIds, MotionPresets.loops(name), MotionPresets.duration(name, tracks, settings))
					})
				}
				// The model presets switch the basic motions and the skeleton presets each as one group.
				if (config.motionBasic) {
					if (config.motionIdle) {
						val physicsDriven = if (config.exportIncludePhysics) {
							physicsGroups.flatMapTo(HashSet()) { it.outputParameters }
						} else emptySet()
						builtin("Idle", "Idle", physicsDriven)
					}
					if (config.motionBlink) builtin("Blink", "Blink")
					if (config.motionNod) builtin("Nod", "Nod")
					if (config.motionShake) builtin("Shake", "Shake")
				}
				if (config.motionSkeleton) {
					for (preset in SkeletonMotions.presets) {
						if (config.rigEdits.motionPresets[preset.name]?.disabled == true) continue
						// A looping preset is another idle, played from the idle group beside the plain one.
						builtin(if (preset.loop) "Idle" else preset.name, preset.name)
					}
				}
				// The user's own motions: a loop joins the idles, a one-shot is its own group under its name.
				val stems = MotionClips.exportStems(clips)
				for (clip in clips.filter { it.builtin == null && it.enabled }) {
					add(if (clip.loop) "Idle" else clip.name, stems.getValue(clip.id), MotionGenerator.clip(clip, parameterIds))
				}
			}
		}
		val motionMap = if (motions.isNotEmpty()) {
			motions.groupBy({ it.first }, { Model3Motion(file = it.second.first) })
		} else null

		val sidecars = buildList {
			if (config.exportIncludePhysics) {
				physics?.let {
					Moc3.readPhysics3(it)
					add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "$baseName.physics3.json", it))
				}
			}
			for ((_, motionPair) in motions) {
				add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Motion, motionPair.first, motionPair.second))
			}
		}
		val manifestTemplate = Model3Json(
			version = 3,
			fileReferences = FileReferences(
				moc = "",
				textures = emptyList(),
				motions = motionMap,
			),
			groups = buildList {
				if (config.motionBasic && config.motionBlink && !config.meshOnly) {
					listOf("ParamEyeLOpen", "ParamEyeROpen").filter(parameterIds::contains).takeIf(List<String>::isNotEmpty)?.let {
						add(Model3Group("Parameter", "EyeBlink", it))
					}
				}
				listOf("ParamMouthOpenY").filter(parameterIds::contains).takeIf(List<String>::isNotEmpty)?.let {
					add(Model3Group("Parameter", "LipSync", it))
				}
			},
		)
		val bundle = Moc3Sidecars.bundle(
			exportPuppet,
			baseName,
			pages = pages,
			sidecars = sidecars,
			source = manifestTemplate,
			canvasToParentSpace = canvasToParentSpaceFor(exportPuppet),
			options = config.moc3ExportOptions(),
		)
		validateBundle(bundle)
		val manifest = bundle.files.single { it.name.endsWith(".model3.json") }.name
		return CubismRuntimeBundle(manifest, bundle.files.map { CubismRuntimeAsset(it.name, it.bytes) }) to bundle.report
	}

	private fun validateBundle(bundle: Moc3Sidecars.Bundle) {
		val byName = bundle.files.associateBy { it.name }
		require(byName.size == bundle.files.size) { tr("error.bundleDuplicate") }
		val manifestFile = bundle.files.single { it.name.endsWith(".model3.json") }
		val manifest = Moc3.readModel3(manifestFile.bytes.decodeToString())
		val references = buildList {
			add(manifest.fileReferences.moc)
			addAll(manifest.fileReferences.textures)
			manifest.fileReferences.physics?.let(::add)
			manifest.fileReferences.pose?.let(::add)
			manifest.fileReferences.userData?.let(::add)
			manifest.fileReferences.displayInfo?.let(::add)
			manifest.fileReferences.expressions.orEmpty().forEach { add(it.file) }
			manifest.fileReferences.motions.orEmpty().values.flatten().forEach { add(it.file) }
		}
		val missing = references.filterNot(byName::containsKey)
		require(missing.isEmpty()) { tr("error.bundleMissing", missing.joinToString()) }
		manifest.fileReferences.displayInfo?.let { Moc3.readCdi3(byName.getValue(it).bytes.decodeToString()) }
		manifest.fileReferences.physics?.let { Moc3.readPhysics3(byName.getValue(it).bytes.decodeToString()) }
		manifest.fileReferences.motions.orEmpty().values.flatten().forEach {
			Json.parseToJsonElement(byName.getValue(it.file).bytes.decodeToString())
		}
	}

	private fun writeContained(root: Path, relativeName: String, bytes: ByteArray): ExportedFile {
		val target = root.resolve(relativeName.replace('/', java.io.File.separatorChar)).normalize()
		require(target.startsWith(root)) { tr("error.outputEscapesRoot", relativeName) }
		Files.createDirectories(target.parent)
		Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
		return ExportedFile(target, bytes.size.toLong())
	}

	private fun safeBaseName(raw: String): String = raw
		.replace(Regex("[^A-Za-z0-9._-]+"), "_")
		.trim('_', '.')
		.ifEmpty { "model" }

	private fun projectReport(
		baseName: String,
		analysis: PipelineAnalysis,
		rig: BuiltRig,
		atlas: PackedAtlas,
		config: PipelineConfig,
		warnings: List<String>,
	): String {
		fun quote(value: String): String = buildString {
			append('"')
			for (character in value) when (character) {
				'"' -> append("\\\"")
				'\\' -> append("\\\\")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				else -> append(character)
			}
			append('"')
		}
		val layers = analysis.layers.joinToString(",\n") { layer ->
			"    {\"source\":${quote(layer.source.name)},\"type\":${quote(layer.semantic.type.name.lowercase())},\"tag\":${quote(layer.semantic.tag.canonicalName)},\"side\":${quote(layer.semantic.side.name)},\"parameter\":${quote(layer.semantic.parameter)},\"switchId\":${layer.semantic.switchId},\"drawable\":${quote(rig.puppet.drawables.firstOrNull { it.name == layer.source.name }?.id?.raw ?: "")}}"
		}
		val physicsIds = PhysicsCatalog.active(analysis, config, rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }).map { it.id }
		val useFrontHair = PhysicsGenerator.FRONT_HAIR_ID in physicsIds
		val useBackHair = PhysicsGenerator.BACK_HAIR_ID in physicsIds
		val useEyeJelly = PhysicsGenerator.EYE_JELLY_ID in physicsIds
		return """
		{
		  "version": 1,
		  "model": ${quote(baseName)},
		  "generator": "PSD2Live 2.0.4",
		  "runtimeTarget": ${quote(rig.puppet.runtimeTarget.name)},
		  "mocVersion": ${rig.puppet.runtimeTarget.mocVersion().byteValue},
		  "cmo3TargetVersionNo": ${rig.puppet.runtimeTarget.cmo3TargetVersionNo()},
		  "cmo3FileFormatVersion": ${quote(rig.puppet.runtimeTarget.cmo3FileFormatVersion())},
		  "canvas": {"width":${analysis.source.widthPx},"height":${analysis.source.heightPx}},
		  "config": {"atlasSize":${config.atlasSize},"meshSpacing":${config.meshSpacing},"headTurnStrength":${config.headTurnStrength},"bodyStrength":${config.bodyStrength},"meshOnly":${config.meshOnly},"generateDeformers":${config.generateDeformers},"exportMotions":${config.exportMotions}},
		  "faceRig": {"algorithm":"perspective-parallelogram-nine-pose-v2","angleX":[-45,0,45],"angleY":[-30,0,30],"initialAngleZ":${rig.initialHeadAngleZ},"centerX":${rig.faceCenterX},"centerY":${rig.faceCenterY},"radiusX":${rig.faceRadiusX},"radiusY":${rig.faceRadiusY}},
		  "deformerHierarchy": {"head":"DeformHeadContainer","face":"DeformFaceNinePose","frontHair":["DeformHairFrontFollow","DeformHairFrontPhysics"],"backHair":["DeformHairBackFollow","DeformHairBackPhysics"]},
		  "physics": {"enabled":${config.generatePhysics && !config.meshOnly},"frontHair":$useFrontHair,"backHair":$useBackHair,"eyeJelly":$useEyeJelly,"groups":[${physicsIds.joinToString(",") { quote(it) }}]},
		  "summary": {"layers":${analysis.layers.size},"drawables":${rig.puppet.drawables.size},"deformers":${rig.puppet.deformers.size},"parameters":${rig.puppet.parameters.size},"atlasPages":${atlas.pages.size}},
		  "layers": [
		$layers
		  ],
		  "warnings": [${warnings.joinToString(",") { quote(it) }}]
		}
		""".trimIndent()
	}
}
