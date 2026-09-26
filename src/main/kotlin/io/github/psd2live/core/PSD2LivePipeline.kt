package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
		return CharacterAnalyzer.analyze(PsdReader.read(bytes), config)
	}

	fun buildPreview(psd: Path, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(inspect(psd, config), config)

	fun buildPreview(
		source: SourceArt,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel = buildPreview(CharacterAnalyzer.analyze(source, config), config, progress)

	fun buildPreview(
		analysis: PipelineAnalysis,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel {
        val effectiveAnalysis = MouthLipLayers.prepare(analysis, config)
        val atlas = AtlasPacker.pack(effectiveAnalysis.layers, config.atlasSize, config.texturePadding, config.textureUpscale, progress)
		val baseRig = RigBuilder.build(effectiveAnalysis, atlas, config, meshCache)
		val rig = baseRig.withRigEdits(config.rigEdits)
		val runtimeBundle = buildRuntimeBundle("psd2live-preview", effectiveAnalysis, atlas, rig, config).first
		return RigPreviewModel(effectiveAnalysis, atlas, rig, config, runtimeBundle, baseRig = baseRig)
	}

	/** A source partition keeps the existing Warp lattices and their coordinate frames verbatim. */
	internal fun buildPreviewAfterLayerSplit(
		current: RigPreviewModel,
		source: SourceArt,
		config: PipelineConfig,
	): RigPreviewModel {
		val analysis = MouthLipLayers.prepare(CharacterAnalyzer.analyze(source, config), config)
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding, config.textureUpscale)
		val ids = current.rig.layerIdByDrawableId.map { (drawableId, layerId) -> layerId to DrawableId(drawableId) }.toMap()
		val generated = RigBuilder.buildPreservingDeformers(
			analysis, atlas, config, meshCache, current.analysis, current.config, ids,
		)
		val baseRig = generated.copy(puppet = generated.puppet.copy(
			deformers = current.baseRig.puppet.deformers,
			parameters = (generated.puppet.parameters + current.baseRig.puppet.parameters).distinctBy { it.id },
		))
		val replayed = baseRig.withRigEdits(config.rigEdits)
		val rig = replayed.copy(puppet = replayed.puppet.copy(
			deformers = current.rig.puppet.deformers,
			parameters = (replayed.puppet.parameters + current.rig.puppet.parameters).distinctBy { it.id },
		))
		val bundle = buildRuntimeBundle("psd2live-preview", analysis, atlas, rig, config).first
		return RigPreviewModel(analysis, atlas, rig, config, bundle, baseRig)
	}

	/** Hierarchy-only edits retain textures but rebuild all parent-space geometry and keyforms. */
	fun rebuildPreview(
		current: RigPreviewModel,
		config: PipelineConfig,
		progress: ProgressListener = ProgressListener { _, _ -> },
	): RigPreviewModel {
		if (current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits, drawOrderOverrides = config.drawOrderOverrides) == config) {
			val baseRig = RigBuilder.build(current.analysis, current.atlas, config, meshCache)
			val rig = baseRig.withRigEdits(config.rigEdits)
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
		val effectiveAnalysis = MouthLipLayers.prepare(analysis, config)
		val atlas = AtlasPacker.pack(
			effectiveAnalysis.layers,
			config.atlasSize,
			config.texturePadding,
			config.textureUpscale,
			progress,
		)
		val baseRig = RigBuilder.build(effectiveAnalysis, atlas, config, meshCache)
		for (drawableId in rebuiltMeshIds) {
			val previousVertexCount = current.baseRig.puppet.drawables
				.firstOrNull { it.id.raw == drawableId }?.mesh?.vertexCount
				?: current.rig.puppet.drawables.firstOrNull { it.id.raw == drawableId }?.mesh?.vertexCount
				?: 0
			val vertexCount = baseRig.puppet.drawables
				.firstOrNull { it.id.raw == drawableId }?.mesh?.vertexCount ?: continue
			retainedEdits = MeshRebuildEdits.reset(
				retainedEdits,
				drawableId,
				previousVertexCount,
				vertexCount,
				meshSettingsChanged = true,
			)
		}
		val retainedRig = baseRig.withRigEdits(retainedEdits)
		val replacementBasePaths = baseRig.puppet.deformPaths.mapTo(HashSet()) { it.id }
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

		val rebasedEdits = retainedEdits.copy(
			authoringJournal = retainedEdits.authoringJournal + deleteCommands + putCommands,
		)
		val rebasedConfig = config.copy(rigEdits = rebasedEdits)
		val rig = baseRig.withRigEdits(rebasedEdits)
		val runtimeBundle = buildRuntimeBundle(
			"psd2live-preview",
			effectiveAnalysis,
			atlas,
			rig,
			rebasedConfig,
		).first
		return RigPreviewModel(effectiveAnalysis, atlas, rig, rebasedConfig, runtimeBundle, baseRig)
	}

	private fun meshSettingsChangedDrawableIds(current: RigPreviewModel, config: PipelineConfig): Set<String> {
		val globalSettingsChanged = current.config.meshSpacing != config.meshSpacing ||
			current.config.meshOuterMargin != config.meshOuterMargin ||
			current.config.meshEdgeMode != config.meshEdgeMode ||
			current.config.meshEdgeWidth != config.meshEdgeWidth ||
			current.config.meshMaxEdgeDistance != config.meshMaxEdgeDistance ||
			current.config.meshInteriorDensity != config.meshInteriorDensity ||
			current.config.meshFillAlgorithm != config.meshFillAlgorithm ||
			current.config.meshSuppressBoundaryDiagonals != config.meshSuppressBoundaryDiagonals ||
			current.config.meshFillParameters != config.meshFillParameters ||
			current.config.alphaThreshold != config.alphaThreshold ||
			current.config.meshOnly != config.meshOnly ||
			current.config.mouthOutlineEnabled != config.mouthOutlineEnabled ||
			current.config.layerOverrides != config.layerOverrides
		return current.rig.puppet.drawables.asSequence()
			.filter { it.mesh != null }
			.filter { drawable ->
				val layerId = current.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
				globalSettingsChanged || current.config.meshOverrides[layerId] != config.meshOverrides[layerId]
			}
			.map { it.id.raw }
			.toSet()
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
		val rig = current.baseRig.withRigEdits(config.rigEdits)
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
		val analysis = CharacterAnalyzer.analyze(source, config)
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
        val analysis = MouthLipLayers.prepare(inputAnalysis, config)
		progress.update(tr("progress.classify"), 0.18)
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding, config.textureUpscale, progress)
		val baseRig = RigBuilder.build(analysis, atlas, config)
		val rig = baseRig.withRigEdits(config.rigEdits)
		val generatedLabel = tr("validation.generated")
		val neutralRig = RigIntegrityValidator.validateNeutralPose(generatedLabel, rig.puppet, rig.sourceBoundsByDrawableId)
		val generatedAngleWarnings = RigIntegrityValidator.validateHeadAnglePoses(generatedLabel, rig.puppet, neutralRig.boundsByDrawableId)
		val generatedWarpWarnings = RigIntegrityValidator.validateDirectionalWarpDimensions(generatedLabel, rig.puppet)
		progress.update(tr("progress.keyforms"), 0.58)
		// CMO3's editable base mesh is canvas-space. The keyform absolutes remain in parent space;
		// Umamo's conversion preserves that mixed-space invariant exactly.
		// Evaluating the mouth at MOUTH_OPEN=1.0f keeps the base mesh in its initial open state matching
		// the authored PSD layer artwork and atlas UV coordinates, avoiding singular affine transforms in CMO3.
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet, mapOf(StandardParameters.MOUTH_OPEN to 1.0f))
		val outputRoot = outputDirectory.toAbsolutePath().normalize()
		val hasFrontHair = analysis.layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val hasBackHair = analysis.layers.any { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val hasEyeJelly = analysis.layers.any { it.semantic.tag == SemanticTag.IRIDES && it.opaquePixels > 0 }
		Files.createDirectories(outputRoot)
		val files = mutableListOf<ExportedFile>()
		val warnings = (analysis.warnings + rig.warnings + neutralRig.warnings + generatedAngleWarnings + generatedWarpWarnings).toMutableList()
		val (runtimeBundle, runtimeReport) = buildRuntimeBundle(baseName, analysis, atlas, rig, config)

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
			val useFrontHairPhysics = hasFrontHair && config.generatePhysics && config.physicsFrontHair && !config.meshOnly
			val useBackHairPhysics = hasBackHair && config.generatePhysics && config.physicsBackHair && !config.meshOnly
			val useEyeJellyPhysics = hasEyeJelly && config.generatePhysics && config.physicsEyeJelly && !config.meshOnly
			val skeletonPhysics = PhysicsGenerator.skeletonRules(config.rigEdits.skeleton,
				rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }).isNotEmpty() ||
				PhysicsGenerator.swingRules(config.rigEdits.swingEdits, rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }).isNotEmpty()
			if (useFrontHairPhysics || useBackHairPhysics || useEyeJellyPhysics ||
				(config.generatePhysics && !config.meshOnly && (config.rigEdits.physicsEdits.isNotEmpty() || skeletonPhysics))) {
				Cmo3PhysicsInjector.inject(converted.model.root as CModelSource, useFrontHairPhysics, useBackHairPhysics,
					useEyeJellyPhysics, config.rigEdits.physicsEdits, config.rigEdits.skeleton, config.rigEdits.swingEdits)
			}
			BezierWarp.configureEditor(converted.model.root as CModelSource)
			val bytes = Cmo3.write(converted.model)
			files += writeContained(outputRoot, "$baseName.cmo3", bytes)
			warnings += converted.report.notices.map { noticeText("CMO3", it) }
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
		return PipelineResult(analysis, files, warnings, RigPreviewModel(analysis, atlas, rig, config, runtimeBundle, baseRig = baseRig))
	}

	internal fun buildRuntimeBundle(
		baseName: String,
		analysis: PipelineAnalysis,
		atlas: PackedAtlas,
		rig: BuiltRig,
		config: PipelineConfig,
	): Pair<CubismRuntimeBundle, org.umamo.interop.ExportReport> {
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet, mapOf(StandardParameters.MOUTH_OPEN to 1.0f))
		val parameterIds = rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }
		val textureFolder = "$baseName.${atlas.pages.firstOrNull()?.image?.width ?: config.atlasSize}"
		val pages = atlas.pages.mapIndexed { index, page ->
			Moc3Sidecars.AtlasPage("$textureFolder/texture_${index.toString().padStart(2, '0')}.png", page.png)
		}
		val hasFrontHair = analysis.layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val hasBackHair = analysis.layers.any { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val hasEyeJelly = analysis.layers.any { it.semantic.tag == SemanticTag.IRIDES && it.opaquePixels > 0 }
		val useFrontHairPhysics = hasFrontHair && config.generatePhysics && config.physicsFrontHair && !config.meshOnly
		val useBackHairPhysics = hasBackHair && config.generatePhysics && config.physicsBackHair && !config.meshOnly
		val useEyeJellyPhysics = hasEyeJelly && config.generatePhysics && config.physicsEyeJelly && !config.meshOnly
		val skeletonPhysics = PhysicsGenerator.skeletonRules(config.rigEdits.skeleton, parameterIds).isNotEmpty() ||
			PhysicsGenerator.swingRules(config.rigEdits.swingEdits, parameterIds).isNotEmpty()
		val physics = if (useFrontHairPhysics || useBackHairPhysics || useEyeJellyPhysics ||
			(config.generatePhysics && !config.meshOnly && (config.rigEdits.physicsEdits.isNotEmpty() || skeletonPhysics))) {
			PhysicsGenerator.generate(useFrontHairPhysics, useBackHairPhysics, useEyeJellyPhysics, parameterIds,
				config.rigEdits.physicsEdits, config.rigEdits.skeleton, config.rigEdits.swingEdits)?.let(CubismJson::normalize)
		} else null

		val motions = buildList<Pair<String, Pair<String, String>>> {
			if (config.exportMotions && !config.meshOnly) {
				val clips = config.rigEdits.motionClips
				fun add(group: String, file: String, motion: String?) {
					motion ?: return
					val json = CubismJson.normalize(motion).also { Json.parseToJsonElement(it) }
					add(group to ("$baseName.$file.motion3.json" to json))
				}
				// An edited generated motion exports its clip in place of the generated one.
				fun builtin(group: String, name: String, generated: () -> String?) {
					val override = MotionClips.overrideOf(clips, name)
					add(group, name.replaceFirstChar(Char::lowercase),
						if (override != null) MotionGenerator.clip(override, parameterIds) else generated())
				}
				if (config.motionIdle) {
					val physicsDriven = if (physics != null && config.exportIncludePhysics) {
						PhysicsGenerator.skeletonRules(config.rigEdits.skeleton, parameterIds).mapTo(HashSet()) { it.outputParameter }
					} else emptySet()
					builtin("Idle", "Idle") { MotionGenerator.idle(parameterIds, config.rigEdits.skeleton, physicsDriven) }
				}
				if (config.motionBlink) builtin("Blink", "Blink") { MotionGenerator.blink(parameterIds) }
				if (config.motionNod) builtin("Nod", "Nod") { MotionGenerator.nod(parameterIds) }
				if (config.motionShake) builtin("Shake", "Shake") { MotionGenerator.shake(parameterIds) }
				if (config.motionSkeleton) {
					val skeleton = config.rigEdits.skeleton
					for (preset in SkeletonMotions.presets) {
						// A looping preset is another idle, played from the idle group beside the plain one.
						val group = if (preset.loop) "Idle" else preset.name
						builtin(group, preset.name) { MotionGenerator.skeleton(preset.tracks(skeleton), parameterIds, loop = preset.loop) }
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
				if (config.motionBlink && !config.meshOnly) {
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
		val hasFrontHair = analysis.layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val hasBackHair = analysis.layers.any { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val hasEyeJelly = analysis.layers.any { it.semantic.tag == SemanticTag.IRIDES && it.opaquePixels > 0 }
		val useFrontHair = hasFrontHair && config.generatePhysics && config.physicsFrontHair && !config.meshOnly
		val useBackHair = hasBackHair && config.generatePhysics && config.physicsBackHair && !config.meshOnly
		val useEyeJelly = hasEyeJelly && config.generatePhysics && config.physicsEyeJelly && !config.meshOnly
		return """
		{
		  "version": 1,
		  "model": ${quote(baseName)},
		  "generator": "PSD2Live 1.3.0",
		  "runtimeTarget": ${quote(rig.puppet.runtimeTarget.name)},
		  "mocVersion": ${rig.puppet.runtimeTarget.mocVersion().byteValue},
		  "cmo3TargetVersionNo": ${rig.puppet.runtimeTarget.cmo3TargetVersionNo()},
		  "cmo3FileFormatVersion": ${quote(rig.puppet.runtimeTarget.cmo3FileFormatVersion())},
		  "canvas": {"width":${analysis.source.widthPx},"height":${analysis.source.heightPx}},
		  "config": {"atlasSize":${config.atlasSize},"meshSpacing":${config.meshSpacing},"headTurnStrength":${config.headTurnStrength},"bodyStrength":${config.bodyStrength},"meshOnly":${config.meshOnly},"generateDeformers":${config.generateDeformers},"exportMotions":${config.exportMotions}},
		  "faceRig": {"algorithm":"perspective-parallelogram-nine-pose-v2","angleX":[-45,0,45],"angleY":[-30,0,30],"initialAngleZ":${rig.initialHeadAngleZ},"centerX":${rig.faceCenterX},"centerY":${rig.faceCenterY},"radiusX":${rig.faceRadiusX},"radiusY":${rig.faceRadiusY}},
		  "deformerHierarchy": {"head":"DeformHeadContainer","face":"DeformFaceNinePose","frontHair":["DeformHairFrontFollow","DeformHairFrontPhysics"],"backHair":["DeformHairBackFollow","DeformHairBackPhysics"]},
		  "physics": {"enabled":${config.generatePhysics && !config.meshOnly},"frontHair":$useFrontHair,"backHair":$useBackHair,"eyeJelly":$useEyeJelly,"preset":"hair-and-eye-pendulum"},
		  "summary": {"layers":${analysis.layers.size},"drawables":${rig.puppet.drawables.size},"deformers":${rig.puppet.deformers.size},"parameters":${rig.puppet.parameters.size},"atlasPages":${atlas.pages.size}},
		  "layers": [
		$layers
		  ],
		  "warnings": [${warnings.joinToString(",") { quote(it) }}]
		}
		""".trimIndent()
	}
}
