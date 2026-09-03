package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.Json
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
import org.umamo.interop.mocVersion
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class PSD2LivePipeline {
	fun inspect(psd: Path, config: PipelineConfig = PipelineConfig()): PipelineAnalysis {
		require(Files.isRegularFile(psd)) { tr("error.psdMissing", psd) }
		val bytes = Files.readAllBytes(psd)
		require(PsdReader.matches(bytes)) { tr("error.invalidPsd", psd) }
		return CharacterAnalyzer.analyze(PsdReader.read(bytes), config)
	}

	fun buildPreview(psd: Path, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(inspect(psd, config), config)

	fun buildPreview(source: SourceArt, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(CharacterAnalyzer.analyze(source, config), config)

	fun buildPreview(analysis: PipelineAnalysis, config: PipelineConfig = PipelineConfig()): RigPreviewModel {
		val effectiveLayers = if (config.deletedLayerIds.isEmpty()) analysis.layers else analysis.layers.filter { it.source.id.raw !in config.deletedLayerIds }
		val effectiveAnalysis = if (effectiveLayers.size != analysis.layers.size) analysis.copy(layers = effectiveLayers) else analysis
		val atlas = AtlasPacker.pack(effectiveAnalysis.layers, config.atlasSize, config.texturePadding)
		val rig = RigBuilder.build(effectiveAnalysis, atlas, config)
		val runtimeBundle = buildRuntimeBundle("psd2live-preview", effectiveAnalysis, atlas, rig, config).first
		return RigPreviewModel(effectiveAnalysis, atlas, rig, config, runtimeBundle)
	}

	fun run(
		psd: Path,
		outputDirectory: Path,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): PipelineResult {
		progress.update(tr("progress.readPsd"), 0.04)
		val analysis = inspect(psd, config)
		progress.update(tr("progress.classify"), 0.18)
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding)
		progress.update(tr("progress.atlas"), 0.38)
		val rig = RigBuilder.build(analysis, atlas, config)
		val generatedLabel = tr("validation.generated")
		val neutralRig = RigIntegrityValidator.validateNeutralPose(generatedLabel, rig.puppet, rig.sourceBoundsByDrawableId)
		RigIntegrityValidator.validateHeadAnglePoses(generatedLabel, rig.puppet, neutralRig.boundsByDrawableId)
		RigIntegrityValidator.validateDirectionalWarpDimensions(generatedLabel, rig.puppet)
		progress.update(tr("progress.keyforms"), 0.58)
		// CMO3's editable base mesh is canvas-space. The keyform absolutes remain in parent space;
		// Umamo's conversion preserves that mixed-space invariant exactly.
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet)
		val baseName = safeBaseName(psd.fileName.toString().substringBeforeLast('.'))
		val outputRoot = outputDirectory.toAbsolutePath().normalize()
		val hasFrontHair = analysis.layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val hasBackHair = analysis.layers.any { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		val hasEyeJelly = analysis.layers.any { it.semantic.tag == SemanticTag.IRIDES && it.opaquePixels > 0 }
		Files.createDirectories(outputRoot)
		val files = mutableListOf<ExportedFile>()
		val warnings = (analysis.warnings + rig.warnings + neutralRig.warnings).toMutableList()
		val (runtimeBundle, runtimeReport) = buildRuntimeBundle(baseName, analysis, atlas, rig, config)

		if (config.exportMoc3) {
			for (file in runtimeBundle.assets) files += writeContained(outputRoot, file.path, file.bytes)
			warnings += runtimeReport.notices.map { noticeText("MOC3", it) }
			val mocBytes = runtimeBundle.assets.first { it.path.endsWith(".moc3") }.bytes
			val reimported = Moc3Import.fromMocDocument(Moc3.read(mocBytes), null)
			validateRigShape("MOC3", exportPuppet, reimported)
			val moc3Label = tr("validation.moc3Readback")
			val mocNeutral = RigIntegrityValidator.validateNeutralPose(moc3Label, reimported, rig.sourceBoundsByDrawableId)
			warnings += mocNeutral.warnings
			RigIntegrityValidator.validateHeadAnglePoses(moc3Label, reimported, mocNeutral.boundsByDrawableId)
			RigIntegrityValidator.validateDirectionalWarpDimensions(moc3Label, reimported)
		}
		progress.update(tr("progress.exportMoc3"), 0.77)

		if (config.exportCmo3) {
			val pages = atlas.pages.map { page ->
				Cmo3Conversion.AtlasPage(page.png, page.image.width, page.image.height)
			}
			val converted = Cmo3Conversion.freshCmo3(
				puppet = exportPuppet,
				pages = pages,
				pageIndexByDrawableId = rig.pageByDrawableId,
				modelName = baseName,
				nowMillis = Instant.now().toEpochMilli(),
				obfuscateKey = 0x42,
			)
			val useFrontHairPhysics = hasFrontHair && config.generatePhysics && config.physicsFrontHair && !config.meshOnly
			val useBackHairPhysics = hasBackHair && config.generatePhysics && config.physicsBackHair && !config.meshOnly
			val useEyeJellyPhysics = hasEyeJelly && config.generatePhysics && config.physicsEyeJelly && !config.meshOnly
			if (useFrontHairPhysics || useBackHairPhysics || useEyeJellyPhysics) {
				Cmo3PhysicsInjector.inject(converted.model.root as CModelSource, useFrontHairPhysics, useBackHairPhysics, useEyeJellyPhysics)
			}
			val bytes = Cmo3.write(converted.model)
			files += writeContained(outputRoot, "$baseName.cmo3", bytes)
			warnings += converted.report.notices.map { noticeText("CMO3", it) }
			val source = Cmo3.read(bytes).root as? CModelSource ?: error(tr("error.cmo3Root"))
			val reimported = Cmo3Import.fromModelSource(source)
			validateRigShape("CMO3", converted.puppet, reimported)
			val cmo3Label = tr("validation.cmo3Readback")
			val cmoNeutral = RigIntegrityValidator.validateNeutralPose(cmo3Label, reimported, rig.sourceBoundsByDrawableId)
			warnings += cmoNeutral.warnings
			RigIntegrityValidator.validateHeadAnglePoses(cmo3Label, reimported, cmoNeutral.boundsByDrawableId)
			RigIntegrityValidator.validateDirectionalWarpDimensions(cmo3Label, reimported)
		}
		progress.update(tr("progress.exportCmo3"), 0.91)

		if (config.exportJson) {
			val report = projectReport(baseName, analysis, rig, atlas, config, warnings)
			Json.parseToJsonElement(report)
			files += writeContained(outputRoot, "$baseName.psd2live.json", report.encodeToByteArray())
		}
		progress.update(tr("progress.validated"), 1.0)
		return PipelineResult(analysis, files, warnings, RigPreviewModel(analysis, atlas, rig, config, runtimeBundle))
	}

	private fun buildRuntimeBundle(
		baseName: String,
		analysis: PipelineAnalysis,
		atlas: PackedAtlas,
		rig: BuiltRig,
		config: PipelineConfig,
	): Pair<CubismRuntimeBundle, org.umamo.interop.ExportReport> {
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet)
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
		val physics = if (useFrontHairPhysics || useBackHairPhysics || useEyeJellyPhysics) {
			PhysicsGenerator.generate(useFrontHairPhysics, useBackHairPhysics, useEyeJellyPhysics)?.let(CubismJson::normalize)
		} else null

		val motions = buildList<Pair<String, Pair<String, String>>> {
			if (config.exportMotions && !config.meshOnly) {
				if (config.motionIdle) {
					val name = "$baseName.idle.motion3.json"
					val json = CubismJson.normalize(MotionGenerator.idle()).also { Json.parseToJsonElement(it) }
					add("Idle" to (name to json))
				}
				if (config.motionBlink) {
					val name = "$baseName.blink.motion3.json"
					val json = CubismJson.normalize(MotionGenerator.blink()).also { Json.parseToJsonElement(it) }
					add("Blink" to (name to json))
				}
				if (config.motionNod) {
					val name = "$baseName.nod.motion3.json"
					val json = CubismJson.normalize(MotionGenerator.nod()).also { Json.parseToJsonElement(it) }
					add("Nod" to (name to json))
				}
				if (config.motionShake) {
					val name = "$baseName.shake.motion3.json"
					val json = CubismJson.normalize(MotionGenerator.shake()).also { Json.parseToJsonElement(it) }
					add("Shake" to (name to json))
				}
			}
		}
		val motionMap = if (motions.isNotEmpty()) {
			motions.groupBy({ it.first }, { Model3Motion(file = it.second.first) })
		} else null

		val sidecars = buildList {
			physics?.let {
				Moc3.readPhysics3(it)
				add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "$baseName.physics3.json", it))
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
			groups = listOf(
				Model3Group("Parameter", "EyeBlink", listOf("ParamEyeLOpen", "ParamEyeROpen")),
				Model3Group("Parameter", "LipSync", listOf("ParamMouthOpenY")),
			),
		)
		val bundle = Moc3Sidecars.bundle(exportPuppet, baseName, pages = pages, sidecars = sidecars, source = manifestTemplate)
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

	private fun validateRigShape(label: String, expected: PuppetModel, actual: PuppetModel) {
		fun <T> requireSame(kind: String, left: Set<T>, right: Set<T>) {
			require(left == right) {
				tr("error.rigShape", label, kind, left - right, right - left)
			}
		}
		requireSame(tr("validation.parameter"), expected.parameters.map { it.id.raw }.toSet(), actual.parameters.map { it.id.raw }.toSet())
		requireSame(tr("validation.deformer"), expected.deformers.map { it.id.raw }.toSet(), actual.deformers.map { it.id.raw }.toSet())
		requireSame(tr("validation.drawable"), expected.drawables.map { it.id.raw }.toSet(), actual.drawables.map { it.id.raw }.toSet())
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
		  "generator": "PSD2Live 0.1.0",
		  "runtimeTarget": ${quote(rig.puppet.runtimeTarget.name)},
		  "mocVersion": ${rig.puppet.runtimeTarget.mocVersion().byteValue},
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
