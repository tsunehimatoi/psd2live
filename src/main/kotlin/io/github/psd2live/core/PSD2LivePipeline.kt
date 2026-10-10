package io.github.psd2live.core

import io.github.psd2live.i18n.tr
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
import org.umamo.interop.ExportNotice
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
import io.github.psd2live.core.legacy.RigGenerationMigration

class PSD2LivePipeline {
	internal val meshCache = PreviewMeshCache()
	private val generatedGeometry = GeneratedGeometryCache()
	fun inspect(psd: Path, config: PipelineConfig = PipelineConfig()): PipelineAnalysis {
		require(Files.isRegularFile(psd)) { tr("error.psdMissing", psd) }
		val bytes = Files.readAllBytes(psd)
		require(PsdReader.matches(bytes)) { tr("error.invalidPsd", psd) }
		return RigGenerationSource.analyze(PsdReader.read(bytes), config)
	}

	fun buildPreview(psd: Path, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(inspect(psd, config), config)

	/**
	 * The preview of [source] under [config]. [previousAtlas] - the atlas of the model a commit starts from - only
	 * lends its unchanged pages and preview PNG strips ([AtlasLayout]); the layout and pixels are the same without it.
	 */
	fun buildPreview(
		source: SourceArt,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
		previousAtlas: PackedAtlas? = null,
	): RigPreviewModel = buildPreview(if (config.rigEdits.importedCmo3 != null) Cmo3ModelImport.analysis(source, config)
		else RigBuildProfile.stage("pipeline: analyze") { RigGenerationSource.analyze(source, config) }, config, progress, previousAtlas)

	fun buildPreview(
		analysis: PipelineAnalysis,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
		previousAtlas: PackedAtlas? = null,
	): RigPreviewModel {
        if (RigLayerDeletion.deferred(config)) {
            return RigLayerDeletion.preview(buildPreview(analysis.source, RigLayerDeletion.generationConfig(config), progress, previousAtlas), config, previousAtlas)
        }
        if (config.rigEdits.importedCmo3 != null) {
            val importedAnalysis = Cmo3ModelImport.analysis(analysis.source, config)
            val (atlas, baseRig) = Cmo3ModelImport.baseRig(analysis.source, config)
            val rig = baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
            val bundle = buildRuntimeBundle("psd2live-preview", importedAnalysis, atlas, rig, config).first
            return RigPreviewModel(importedAnalysis, atlas, rig, config, bundle, PreviewRigSources.of(baseRig))
        }
        val inputs = generationInputs(analysis, config, progress, previousAtlas)
        val (effectiveAnalysis, fullAtlas, baseRig) = generatedBase(inputs, config, progress)
		// The base packs superseded layers (the originals of a split) too; once no mesh samples them the model
		// packs only the layers it shows, as exports do.
		val (atlas, rig) = RigLayerDeletion.compact(fullAtlas, effectiveAnalysis, effectiveAnalysis,
			baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides), config, previousAtlas)
		val runtimeBundle = buildRuntimeBundle("psd2live-preview", effectiveAnalysis, atlas, rig, config).first
		return RigPreviewModel(effectiveAnalysis, atlas, rig, config, runtimeBundle, PreviewRigSources.of(baseRig, inputs.bindingKey),
			generationAtlas = fullAtlas.takeIf { it !== atlas })
	}

	/** The binding key of the atlas [source] under [config] binds its generated base to ([materializedPreview]). */
	internal fun bindingKey(source: SourceArt, config: PipelineConfig): String {
		val generation = RigLayerDeletion.generationConfig(config)
		return generationInputs(RigGenerationSource.analyze(source, generation), generation, ProgressListener { _, _ -> }).bindingKey
	}

	/** The generated base of [source] under [config], deletions not applied, and the binding key of its atlas. */
	internal fun generatedBaseOf(source: SourceArt, config: PipelineConfig): Pair<BuiltRig, String> {
		val generation = RigLayerDeletion.generationConfig(config)
		val inputs = generationInputs(RigGenerationSource.analyze(source, generation), generation, ProgressListener { _, _ -> })
		return generatedBase(inputs, generation, ProgressListener { _, _ -> }).rig to inputs.bindingKey
	}

	/** Whether a preview of [config] can come from a stored authored rig: a generated model, not an imported one. */
	fun materializable(config: PipelineConfig): Boolean = config.rigEdits.importedCmo3 == null

	/**
	 * The preview of [source] under [config] from [authored] - the authored state of [config]'s edits, bound to an atlas
	 * under [bindingKey] - without generating the base rig or replaying the journal. Null when the document now packs to
	 * an atlas with another binding key. The returned model generates its base rig only when something asks for it.
	 */
	internal fun materializedPreview(source: SourceArt, config: PipelineConfig, authored: AuthoredRig, bindingKey: String,
	                                 progress: ProgressListener = ProgressListener { _, _ -> },
	                                 previousAtlas: PackedAtlas? = null, rebind: Boolean = false): RigPreviewModel? {
		require(materializable(config)) { "This document's preview is not built from an authored rig" }
		// Deletions after a journal that creates or splits meshes filter the full model, as [buildPreview] does.
		if (RigLayerDeletion.deferred(config)) return materializedPreview(source, RigLayerDeletion.generationConfig(config), authored, bindingKey,
			progress, previousAtlas, rebind)?.let { RigLayerDeletion.preview(it, config, previousAtlas) }
		val analysis = RigBuildProfile.stage("pipeline: analyze") { RigGenerationSource.analyze(source, config) }
		val inputs = generationInputs(analysis, config, progress, previousAtlas)
		// Another atlas: [rebind] moves the texture coordinates onto it through the canvas (the data is the authored rig);
		// otherwise the caller builds by generation, which binds exactly.
		@Suppress("NAME_SHADOWING")
		val authored = if (inputs.bindingKey == bindingKey) authored else if (!rebind) return null else
			PuppetSourceAtlas.build(inputs.analyses.textures, inputs.atlas).let { (atlas, sources) -> authored.reboundTo(atlas, sources) }
		val (atlas, rig) = RigLayerDeletion.compact(inputs.atlas, inputs.visible, inputs.visible,
			authored.finished(config.rigEdits, config.layerVisibility, config.drawOrderOverrides), config, previousAtlas)
		val runtimeBundle = buildRuntimeBundle("psd2live-preview", inputs.visible, atlas, rig, config).first
		return RigPreviewModel(inputs.visible, atlas, rig, config, runtimeBundle,
			PreviewRigSources.materialized(config.rigEdits, authored, inputs.bindingKey) { generatedBase(inputs, config, ProgressListener { _, _ -> }).rig },
			generationAtlas = inputs.atlas.takeIf { it !== atlas })
	}

	private fun PipelineConfig.withoutTextureLayout() =
		if (textureOverrides.isEmpty() && atlasArrangement == null) this else copy(textureOverrides = emptyMap(), atlasArrangement = null)

	private data class GeneratedBase(val analysis: PipelineAnalysis, val atlas: PackedAtlas, val rig: BuiltRig)

	/** What [generatedBase] computes before the rig builder runs: the analyses and the atlas the base is bound to. */
	private class GenerationInputs(val baselineConfig: PipelineConfig, val analyses: RigGenerationSource.Analyses,
	                               val generationConfig: PipelineConfig, val atlas: PackedAtlas, val visible: PipelineAnalysis) {
		/**
		 * What binding a rig to [atlas] reads: every placement, the page sizes and the texture layers' rectangles. A rig
		 * bound under an equal key has the texture coordinates a rig bound now would have.
		 */
		val bindingKey: String by lazy {
			val text = buildString {
				atlas.placementByLayerId.toSortedMap().forEach { (layer, placement) -> append("|p:").append(layer).append('=').append(placement) }
				atlas.pages.forEach { append("|page:").append(it.image.width).append('x').append(it.image.height) }
				analyses.textures.layers.forEach { layer -> val s = layer.source
					append("|l:").append(s.id.raw).append(':').append(s.bounds).append(':').append(s.raster.width).append('x').append(s.raster.height)
				}
			}
			java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
		}
	}

	private fun generationInputs(input: PipelineAnalysis, config: PipelineConfig, progress: ProgressListener,
	                             previousAtlas: PackedAtlas? = null): GenerationInputs {
		val baselineConfig = RigGenerationBaseline.restore(MeshGenerationBaseline.restore(config))
		val analyses = RigBuildProfile.stage("pipeline: analysis prepare") { RigGenerationSource.prepare(input, baselineConfig, config) }
		val generationConfig = baselineConfig.copy(parentOverrides = generatedParentOverrides(config))
		val atlas = RigBuildProfile.stage("pipeline: atlas layout") { AtlasLayout.pack(analyses.textures.layers, config, progress, previousAtlas) }
		val visible = ArtPrimitiveJournal.visibleAnalysis(analyses.textures, config.rigEdits)
		return GenerationInputs(baselineConfig, analyses, generationConfig, atlas, visible)
	}

	/**
	 * The parent overrides the generation reads: those of the layers it meshes. A layer the journal creates a mesh for
	 * (an imported image, a partition piece) hangs where its creation puts it, so its override is not a generation input.
	 */
	internal fun generatedParentOverrides(config: PipelineConfig): Map<String, String?> {
		val createdLayers = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
			.mapTo(HashSet()) { it.getValue("layer_id").jsonPrimitive.content }
		createdLayers += SourcePartitionJournal.commands(config.rigEdits).flatMap(SourcePartitionJournal::pieces)
			.map { it.getValue("layer_id").jsonPrimitive.content }
		return config.parentOverrides - createdLayers
	}

	/** Preview, path normalization and export must use the same saved generation input. */
	private fun generatedBase(input: PipelineAnalysis, config: PipelineConfig, progress: ProgressListener,
	                          previousAtlas: PackedAtlas? = null): GeneratedBase =
		generatedBase(generationInputs(input, config, progress, previousAtlas), config, progress)

	private fun generatedBase(inputs: GenerationInputs, config: PipelineConfig, progress: ProgressListener): GeneratedBase {
		val baselineConfig = inputs.baselineConfig
		val analyses = inputs.analyses
		val generationConfig = inputs.generationConfig
		val atlas = inputs.atlas
		val visible = inputs.visible
		if (config.generationSource == null) return GeneratedBase(visible, atlas,
			withoutCreatedMeshes(RigBuilder.build(analyses.geometry, atlas, generationConfig, meshCache), config))
		// The geometry rig's UVs are moved onto the texture atlas below, so its own layout ignores the texture
		// overrides and the stored arrangement, and a texture commit keeps hitting the geometry cache.
		val geometryConfig = generationConfig.withoutTextureLayout()
		val geometry = generatedGeometry.getOrPut(analyses.geometry.source, geometryConfig, baselineConfig.withoutTextureLayout()) {
			val geometryAtlas = RigBuildProfile.stage("pipeline: geometry atlas layout") {
				AtlasLayout.pack(analyses.geometry.layers, config.withoutTextureLayout(), progress)
			}
			GeneratedGeometryCache.Entry(geometryAtlas, RigBuilder.build(analyses.geometry, geometryAtlas, geometryConfig, meshCache))
		}
		// The repacked base depends on the geometry rig, its layout, the layout and the texture layers' metadata -
		// never on their pixels - so a repaint that keeps every tile's size and spot gets the very same base instance,
		// and replay checkpoints keyed by it keep hitting. The geometry rig counts by identity: the rig builder's
		// stage cache hands out the same instance whenever every stage input is unchanged, even when the generation
		// config differs in what no stage reads (a journal entry), which misses the geometry cache.
		val key = BoundBaseKey(geometry.rig, geometry.atlas.placementByLayerId, geometry.atlas.pages.map { it.image.width to it.image.height },
			atlas.placementByLayerId, atlas.pages.map { it.image.width to it.image.height },
			analyses.textures.layers.map { layer -> val s = layer.source
				listOf(s.id.raw, s.name, s.groupPath, s.visible, s.idIsStable, s.bounds, s.raster.width, s.raster.height) },
			createdIds(config))
		val rig = synchronized(boundBases) { boundBases[key] } ?: RigBuildProfile.stage("pipeline: repack") { withoutCreatedMeshes(
			RigGenerationSource.repack(geometry.rig, analyses.geometry, geometry.atlas, analyses.textures, atlas), config) }
			.also { synchronized(boundBases) { boundBases[key] = it } }
		return GeneratedBase(visible, atlas, rig)
	}

	private data class BoundBaseKey(val geometry: BuiltRig, val geometryPlacements: Map<String, AtlasPlacement>, val geometryPages: List<Pair<Int, Int>>,
	                                val placements: Map<String, AtlasPlacement>,
	                                val pages: List<Pair<Int, Int>>, val layers: List<List<Any>>, val created: Set<String>) {
		override fun equals(other: Any?) = other is BoundBaseKey && geometry === other.geometry && geometryPlacements == other.geometryPlacements &&
			geometryPages == other.geometryPages && placements == other.placements &&
			pages == other.pages && layers == other.layers && created == other.created
		override fun hashCode() = java.util.Objects.hash(System.identityHashCode(geometry), geometryPlacements, geometryPages, placements, pages, layers, created)
	}

	private val boundBases = object : LinkedHashMap<BoundBaseKey, BuiltRig>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BoundBaseKey, BuiltRig>?) = size > 4
	}

	private fun createdIds(config: PipelineConfig): Set<String> =
		config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
			.mapTo(HashSet()) { it.getValue("id").jsonPrimitive.content } +
			SourcePartitionJournal.commands(config.rigEdits).flatMap(SourcePartitionJournal::pieces).map { it.getValue("id").jsonPrimitive.content }

    /** Keep the original analysis/parent frames, but let journal creations own their mesh IDs. */
    private fun withoutCreatedMeshes(rig: BuiltRig, config: PipelineConfig): BuiltRig {
        val ids = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
            .mapTo(HashSet()) { it.getValue("id").jsonPrimitive.content }
        ids += SourcePartitionJournal.commands(config.rigEdits).flatMap(SourcePartitionJournal::pieces)
            .map { it.getValue("id").jsonPrimitive.content }
        if (ids.isEmpty()) return rig
        val deleted = ids.mapTo(HashSet()) { DrawableId(it) }
        return rig.copy(puppet = rig.puppet.withDrawablesDeleted(deleted), unbound = rig.unbound?.withDrawablesDeleted(deleted),
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
		val atlas = AtlasLayout.pack(analysis.layers, config)
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
		return RigPreviewModel(analysis, atlas, rig, committedConfig, bundle, PreviewRigSources.of(baseRig))
	}

	/** Hierarchy-only edits retain textures but rebuild all parent-space geometry and keyforms. */
	fun rebuildPreview(
		current: RigPreviewModel,
		config: PipelineConfig,
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel {
        // A generated model's regeneration merges and checkpoints where the document builds ([RigRegenerationCheckpoint]).
        if (!(materializable(config) && materializable(current.config)) && RigGenerationChange.changed(current, config)) {
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
		if (MeshGenerationBaseline.present(config.rigEdits)) return buildPreview(current.analysis.source, config, progress, current.atlas)
		if (current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits, drawOrderOverrides = config.drawOrderOverrides,
				hairSimulationFront = config.hairSimulationFront, hairSimulationBack = config.hairSimulationBack) == config) {
			val baseRig = if (config.generationSource == null) RigBuilder.build(current.analysis, current.atlas, config, meshCache)
				else generatedBase(current.analysis, config, progress, current.atlas).rig
			val rig = baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
			val bundle = buildRuntimeBundle("psd2live-preview", current.analysis, current.atlas, rig, config).first
			return current.copy(rig = rig, config = config, runtimeBundle = bundle, sources = PreviewRigSources.of(baseRig))
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
			val meshAtlas = AtlasLayout.pack(meshAnalysis.layers, config, progress)
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
		val (effectiveAnalysis, atlas, baseRig) = generatedBase(analysis, config, progress, current.atlas)
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
		return RigPreviewModel(effectiveAnalysis, atlas, rig, rebasedConfig, runtimeBundle, PreviewRigSources.of(baseRig))
	}

	private fun globalMeshControlsChanged(before: PipelineConfig, config: PipelineConfig) =
		before.meshSpacing != config.meshSpacing || before.meshOuterMargin != config.meshOuterMargin ||
		before.meshEdgeMode != config.meshEdgeMode || before.meshEdgeWidth != config.meshEdgeWidth ||
		before.meshMaxEdgeDistance != config.meshMaxEdgeDistance || before.meshInteriorDensity != config.meshInteriorDensity ||
		before.meshFillAlgorithm != config.meshFillAlgorithm || before.meshSuppressBoundaryDiagonals != config.meshSuppressBoundaryDiagonals ||
		before.meshFillParameters != config.meshFillParameters || before.meshUnits != config.meshUnits ||
		before.meshTrace != config.meshTrace || before.meshWrap != config.meshWrap || before.alphaThreshold != config.alphaThreshold

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
		return current.copy(config = config, runtimeBundle = runtimeBundle)
	}

	/** Fast incremental update for rig and keyform edits replayed onto the cached base rig. */
	internal fun updateRigEdits(
		current: RigPreviewModel,
		config: PipelineConfig,
		baseName: String = "psd2live-preview",
		stored: AuthoredRig? = null,
	): RigPreviewModel {
		// A deferred deletion model keeps the complete generated base and atlas; only its replayed rig and
		// analysis are filtered. Replay onto that base and filter the same way instead of regenerating it.
		if (RigLayerDeletion.deferred(config) != RigLayerDeletion.deferred(current.config)) return buildPreview(current.analysis.source, config)
		// Entries added to the journal act on the current authored rig, and other edits of the journal replay from its last
		// checkpoint: no base, no replay of the entries before it.
		// [stored] is the authored state of [config]'s edits already built (an undo, a redo).
		val appended = if (config.rigEdits == RigEditOverlay.Empty) null
			else stored?.let { val bound = current.authored.rig.puppet; it.reboundTo(bound.atlas, bound.sources) }
				?: current.authoredWithoutBase(config.rigEdits)
		val sources = appended?.let { authored ->
			val previous = current.sources
			PreviewRigSources.materialized(config.rigEdits, authored, previous.bindingKey) { previous.base }
		} ?: current.sources
		val finished = appended?.finished(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
			?: current.baseRig.withRigEdits(config.rigEdits, config.layerVisibility, config.drawOrderOverrides)
		val replayed = RigLayerDeletion.rig(finished, current.analysis, config)
		val rig = current.generationAtlas?.let { full ->
			RigLayerDeletion.rebind(replayed, current.analysis, full, current.analysis, current.atlas) ?: return buildPreview(current.analysis.source, config)
		} ?: replayed
		val (runtimeBundle, _) = buildRuntimeBundle(baseName, current.analysis, current.atlas, rig, config)
		return current.copy(
			rig = rig,
			config = config,
			runtimeBundle = runtimeBundle,
			sources = sources,
		)
	}

	/**
	 * Whether [a] and [b] are the same art for generation and texturing: equal, or different only in where layers were
	 * moved as a whole ([io.github.psd2live.project.LayerTransform]), which neither generation nor the atlas reads.
	 */
	fun sameArt(a: SourceArt, b: SourceArt): Boolean {
		if (a === b || a == b) return true
		if (a.widthPx != b.widthPx || a.heightPx != b.heightPx || a.groups != b.groups || a.layers.size != b.layers.size) return false
		return a.layers.indices.all { i ->
			val x = a.layers[i]; val y = b.layers[i]
			x === y || x == y || (x is io.github.psd2live.project.WorkspaceSourceLayer && y is io.github.psd2live.project.WorkspaceSourceLayer &&
				x.copy(layerTransform = null) == y.copy(layerTransform = null))
		}
	}

	fun canFastUpdateRig(
		current: RigPreviewModel?,
		source: SourceArt,
		config: PipelineConfig,
	): Boolean {
		if (current == null) return false
		if (current.config.rigEdits.importedCmo3 != config.rigEdits.importedCmo3) return false
		if (!sameArt(current.analysis.source, source)) return false
		if (current.config.rigEdits.skeleton != config.rigEdits.skeleton) return false
		if (RigBuilder.skeletonJournalInputs(current.config) != RigBuilder.skeletonJournalInputs(config)) return false
		return current.config.copy(rigEdits = config.rigEdits) == config
	}

	fun run(
		psd: Path,
		outputDirectory: Path,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): PipelineResult {
		progress.update(tr("progress.readPsd"), 0.04)
		val model = buildPreview(inspect(psd, config), config, building(progress))
		return export(model, psd.fileName.toString(), outputDirectory, progress)
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
		return export(buildPreview(source, config, building(progress)), sourceName, outputDirectory, progress)
	}

	/** The build's own progress, in the part of an export before the model is written. */
	private fun building(progress: ProgressListener) = ProgressListener { stage, fraction -> progress.update(stage, 0.04 + fraction * 0.54) }

	/**
	 * Writes [model] - the rig the editor shows, as built - as the formats its config enables, each read back and
	 * checked. The workspace exports its committed model this way, so the files are the preview, never a rebuild.
	 */
	fun export(model: RigPreviewModel, sourceName: String, outputDirectory: Path,
	           progress: ProgressListener = ProgressListener { _, _ -> }): PipelineResult {
		val (analysis, atlas, rig, config) = model
		val baseName = safeBaseName(sourceName.substringBeforeLast('.'))
		val imported = config.rigEdits.importedCmo3 != null
		val generatedLabel = tr("validation.generated")
		val neutralRig = RigIntegrityValidator.validateNeutralPose(generatedLabel, rig.puppet, rig.sourceBoundsByDrawableId)
		val generatedAngleWarnings = RigIntegrityValidator.validateHeadAnglePoses(generatedLabel, rig.puppet, neutralRig.boundsByDrawableId)
		val generatedWarpWarnings = RigIntegrityValidator.validateDirectionalWarpDimensions(generatedLabel, rig.puppet)
		progress.update(tr("progress.keyforms"), 0.58)
		// CMO3's editable base mesh is canvas-space. The keyform absolutes remain in parent space;
		// Umamo's conversion preserves that mixed-space invariant exactly.
		// Evaluating the mouth at MOUTH_OPEN=1.0f keeps the base mesh in its initial open state matching
		// the authored PSD layer artwork and atlas UV coordinates, avoiding singular affine transforms in CMO3.
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet, if (imported) emptyMap() else mapOf(StandardParameters.MOUTH_OPEN to 1.0f))
		val outputRoot = outputDirectory.toAbsolutePath().normalize()
		Files.createDirectories(outputRoot)
		val files = mutableListOf<ExportedFile>()
		val warnings = (analysis.warnings + rig.warnings + neutralRig.warnings + generatedAngleWarnings + generatedWarpWarnings).toMutableList()
		val (runtimeBundle, runtimeReport) = buildRuntimeBundle(baseName, analysis, atlas, rig, config, validate = true, preview = false)

		if (config.exportMoc3) {
			for (file in runtimeBundle.assets) files += writeContained(outputRoot, file.path, file.bytes)
			warnings += runtimeReport.notices.map { noticeText("MOC3", it) }
			val mocBytes = runtimeBundle.assets.first { it.path.endsWith(".moc3") }.bytes
			val reimported = Moc3Import.fromMocDocument(Moc3.read(mocBytes), null)
			warnings += validateRigShape("MOC3", exportPuppet, reimported)
			val moc3Label = tr("validation.moc3Readback")
			val mocNeutral = RigIntegrityValidator.validateNeutralPose(moc3Label, reimported, rig.sourceBoundsByDrawableId)
			warnings += mocNeutral.warnings
			warnings += RigIntegrityValidator.validateHeadAnglePoses(moc3Label, reimported, mocNeutral.boundsByDrawableId)
			warnings += RigIntegrityValidator.validateDirectionalWarpDimensions(moc3Label, reimported)
		}
		progress.update(tr("progress.exportMoc3"), 0.77)

		if (config.exportCmo3) {
			val ir = RigIrCompiler.compile(analysis, atlas, rig, config, tileArt = true)
			val cmo3 = io.github.psd2live.targets.cubism.Cmo3Target { BezierWarp.configureEditor(it, config.rigEdits) }
			val cmo3Options = io.github.psd2live.format.compile.ExportOptions(baseName)
			val converted = cmo3.convert(ir, cmo3Options)
			val bytes = Cmo3.write(converted.model)
			files += writeContained(outputRoot, "$baseName.cmo3", bytes)
			warnings += converted.report.notices.map { noticeText("CMO3", it) }
			warnings += cmo3.textureLosses(ir, cmo3Options).map { "CMO3: ${it.objectId}: ${it.note}" }
			cmo3.can3(ir, cmo3Options, converted)?.let { files += writeContained(outputRoot, "$baseName.can3", it) }
			warnings += cmo3.can3Losses(ir, cmo3Options).map { "CAN3: ${it.objectId}: ${it.note}" }
			val source = Cmo3.read(bytes).root as? CModelSource ?: error(tr("error.cmo3Root"))
			val reimported = Cmo3Import.fromModelSource(source)
			warnings += validateRigShape("CMO3", converted.puppet, reimported)
			val cmo3Label = tr("validation.cmo3Readback")
			val cmoNeutral = RigIntegrityValidator.validateNeutralPose(cmo3Label, reimported, rig.sourceBoundsByDrawableId)
			warnings += cmoNeutral.warnings
			warnings += RigIntegrityValidator.validateHeadAnglePoses(cmo3Label, reimported, cmoNeutral.boundsByDrawableId)
			warnings += RigIntegrityValidator.validateDirectionalWarpDimensions(cmo3Label, reimported)
		}
		progress.update(tr("progress.exportCmo3"), 0.91)

		if (config.exportJson) {
			val report = projectReport(baseName, analysis, rig, atlas, config, warnings)
			Json.parseToJsonElement(report)
			files += writeContained(outputRoot, "$baseName.psd2live.json", report.encodeToByteArray())
		}
		progress.update(tr("progress.validated"), 1.0)
		return PipelineResult(analysis, files, warnings, model)
	}

	/**
	 * The moc3 runtime bundle of [rig]. [validate] reads the manifest and every sidecar back: files written for
	 * the user always are, while the editor's preview bundles - the same compile, rebuilt on every edit and only
	 * loaded by the preview - skip it unless [validatesPreviewBundles]. A [preview] bundle carries the pages'
	 * strip-encoded PNGs ([AtlasPage.previewPng]), which re-encode only changed rows; files written for the user
	 * carry the canonical encoding.
	 */
	internal fun buildRuntimeBundle(
		baseName: String,
		analysis: PipelineAnalysis,
		atlas: PackedAtlas,
		rig: BuiltRig,
		config: PipelineConfig,
		validate: Boolean = validatesPreviewBundles(),
		preview: Boolean = true,
	): Pair<CubismRuntimeBundle, org.umamo.interop.ExportReport> {
		val ir = RigIrCompiler.compile(analysis, if (preview) atlas.forPreview() else atlas, rig, config)
		val bundle = io.github.psd2live.targets.cubism.Moc3Target.bundle(ir, moc3ExportOptions(baseName, config))
		if (validate) validateBundle(bundle)
		val manifest = bundle.files.single { it.name.endsWith(".model3.json") }.name
		return CubismRuntimeBundle(manifest, bundle.files.map { CubismRuntimeAsset(it.name, it.bytes) }) to bundle.report
	}

	/** The export settings of [config] as the moc3 target's options. */
	internal fun moc3ExportOptions(baseName: String, config: PipelineConfig): io.github.psd2live.format.compile.ExportOptions {
		val options = config.moc3ExportOptions()
		return io.github.psd2live.format.compile.ExportOptions(baseName, settings = buildMap {
			put("hidden_parts", options.exportHiddenParts.toString()); put("hidden_meshes", options.exportHiddenDrawables.toString())
			put("guide_parts", options.exportGuideImageParts.toString()); put("physics", options.includePhysics.toString())
			put("user_data", options.includeUserData.toString()); put("display_info", options.includeDisplayInfo.toString())
			options.pixelsPerUnitOverride?.let { put("pixels_per_unit", it.toString()) }
		})
	}

	/**
	 * Whether preview bundles are read back too: as the `psd2live.validatePreviewBundles` system property says,
	 * otherwise whenever assertions are on (the test JVMs), so a malformed sidecar still fails in tests.
	 */
	internal fun validatesPreviewBundles(): Boolean =
		System.getProperty("psd2live.validatePreviewBundles")?.toBooleanStrictOrNull() ?: PSD2LivePipeline::class.java.desiredAssertionStatus()

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

	private fun validateRigShape(label: String, expected: PuppetModel, actual: PuppetModel): List<String> {
		val warnings = mutableListOf<String>()
		fun <T> checkSame(kind: String, left: Set<T>, right: Set<T>) {
			if (left != right) {
				warnings += tr("error.rigShape", label, kind, left - right, right - left)
			}
		}
		checkSame(tr("validation.parameter"), expected.parameters.map { it.id.raw }.toSet(), actual.parameters.map { it.id.raw }.toSet())
		checkSame(tr("validation.deformer"), expected.deformers.map { it.id.raw }.toSet(), actual.deformers.map { it.id.raw }.toSet())
		checkSame(tr("validation.drawable"), expected.drawables.map { it.id.raw }.toSet(), actual.drawables.map { it.id.raw }.toSet())
		return warnings
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

	private fun noticeText(format: String, notice: ExportNotice): String =
		when (notice) {
			is ExportNotice.MissingSourceArt ->
				tr("warning.sourceArtRebuilt", format, notice.pageCount)
			else -> tr("warning.exportNotice", format, notice)
		}

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
		  "generator": "PSD2Live 3.2.0",
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
