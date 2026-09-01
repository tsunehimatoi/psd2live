package io.github.autolive2d.core

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

class AutoLive2DPipeline {
	fun inspect(psd: Path, config: PipelineConfig = PipelineConfig()): PipelineAnalysis {
		require(Files.isRegularFile(psd)) { "PSD 文件不存在：$psd" }
		val bytes = Files.readAllBytes(psd)
		require(PsdReader.matches(bytes)) { "不是有效的 PSD 文件：$psd" }
		return CharacterAnalyzer.analyze(PsdReader.read(bytes), config)
	}

	fun buildPreview(psd: Path, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(inspect(psd, config), config)

	fun buildPreview(source: SourceArt, config: PipelineConfig = PipelineConfig()): RigPreviewModel =
		buildPreview(CharacterAnalyzer.analyze(source, config), config)

	fun buildPreview(analysis: PipelineAnalysis, config: PipelineConfig = PipelineConfig()): RigPreviewModel {
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding)
		val rig = RigBuilder.build(analysis, atlas, config)
		return RigPreviewModel(analysis, atlas, rig, config)
	}

	fun run(
		psd: Path,
		outputDirectory: Path,
		config: PipelineConfig = PipelineConfig(),
		progress: ProgressListener = ProgressListener { _, _ -> },
	): PipelineResult {
		progress.update("读取 PSD", 0.04)
		val analysis = inspect(psd, config)
		progress.update("语义识别与左右拆分", 0.18)
		val atlas = AtlasPacker.pack(analysis.layers, config.atlasSize, config.texturePadding)
		progress.update("生成贴图集", 0.38)
		val rig = RigBuilder.build(analysis, atlas, config)
		val neutralRig = RigIntegrityValidator.validateNeutralPose("生成模型", rig.puppet, rig.sourceBoundsByDrawableId)
		RigIntegrityValidator.validateHeadAnglePoses("生成模型", rig.puppet, neutralRig.boundsByDrawableId)
		RigIntegrityValidator.validateDirectionalWarpDimensions("生成模型", rig.puppet)
		progress.update("生成参数与关键形态", 0.58)
		// CMO3's editable base mesh is canvas-space. The keyform absolutes remain in parent space;
		// Umamo's conversion preserves that mixed-space invariant exactly.
		val exportPuppet = restMeshesToCanvasSpace(rig.puppet)
		val baseName = safeBaseName(psd.fileName.toString().substringBeforeLast('.'))
		val outputRoot = outputDirectory.toAbsolutePath().normalize()
		val hasFrontHair = analysis.layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val hasBackHair = analysis.layers.any { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		Files.createDirectories(outputRoot)
		val files = mutableListOf<ExportedFile>()
		val warnings = (analysis.warnings + rig.warnings + neutralRig.warnings).toMutableList()

		if (config.exportMoc3) {
			val textureFolder = "$baseName.${atlas.pages.firstOrNull()?.image?.width ?: config.atlasSize}"
			val pages = atlas.pages.mapIndexed { index, page ->
				Moc3Sidecars.AtlasPage("$textureFolder/texture_${index.toString().padStart(2, '0')}.png", page.png)
			}
			val physics = if (config.generatePhysics) PhysicsGenerator.generate(hasFrontHair, hasBackHair) else null
			val motionName = "$baseName.idle.motion3.json"
			val motion = MotionGenerator.idle().also { Json.parseToJsonElement(it) }
			val sidecars = buildList {
				physics?.let {
					Moc3.readPhysics3(it)
					add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "$baseName.physics3.json", it))
				}
				add(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Motion, motionName, motion))
			}
			val manifestTemplate = Model3Json(
				version = 3,
				fileReferences = FileReferences(
					moc = "",
					textures = emptyList(),
					motions = mapOf("Idle" to listOf(Model3Motion(file = motionName))),
				),
				groups = listOf(
					Model3Group("Parameter", "EyeBlink", listOf("ParamEyeLOpen", "ParamEyeROpen")),
					Model3Group("Parameter", "LipSync", listOf("ParamMouthOpenY")),
				),
			)
			val bundle = Moc3Sidecars.bundle(exportPuppet, baseName, pages = pages, sidecars = sidecars, source = manifestTemplate)
			validateBundle(bundle)
			for (file in bundle.files) files += writeContained(outputRoot, file.name, file.bytes)
			warnings += bundle.report.notices.map { noticeText("MOC3", it) }
			val mocBytes = bundle.files.first { it.name == bundle.mocFileName }.bytes
			val reimported = Moc3Import.fromMocDocument(Moc3.read(mocBytes), null)
			validateRigShape("MOC3", exportPuppet, reimported)
			val mocNeutral = RigIntegrityValidator.validateNeutralPose("MOC3 回读", reimported, rig.sourceBoundsByDrawableId)
			warnings += mocNeutral.warnings
			RigIntegrityValidator.validateHeadAnglePoses("MOC3 回读", reimported, mocNeutral.boundsByDrawableId)
			RigIntegrityValidator.validateDirectionalWarpDimensions("MOC3 回读", reimported)
		}
		progress.update("导出 MOC3 文件族", 0.77)

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
			if (config.generatePhysics) {
				Cmo3PhysicsInjector.inject(converted.model.root as CModelSource, hasFrontHair, hasBackHair)
			}
			val bytes = Cmo3.write(converted.model)
			files += writeContained(outputRoot, "$baseName.cmo3", bytes)
			warnings += converted.report.notices.map { noticeText("CMO3", it) }
			val source = Cmo3.read(bytes).root as? CModelSource ?: error("CMO3 根节点不是 CModelSource")
			val reimported = Cmo3Import.fromModelSource(source)
			validateRigShape("CMO3", converted.puppet, reimported)
			val cmoNeutral = RigIntegrityValidator.validateNeutralPose("CMO3 回读", reimported, rig.sourceBoundsByDrawableId)
			warnings += cmoNeutral.warnings
			RigIntegrityValidator.validateHeadAnglePoses("CMO3 回读", reimported, cmoNeutral.boundsByDrawableId)
			RigIntegrityValidator.validateDirectionalWarpDimensions("CMO3 回读", reimported)
		}
		progress.update("导出 CMO3 工程", 0.91)

		val report = projectReport(baseName, analysis, rig, atlas, config, warnings)
		Json.parseToJsonElement(report)
		files += writeContained(outputRoot, "$baseName.autolive2d.json", report.encodeToByteArray())
		progress.update("校验完成", 1.0)
		return PipelineResult(analysis, files, warnings, RigPreviewModel(analysis, atlas, rig, config))
	}

	private fun validateBundle(bundle: Moc3Sidecars.Bundle) {
		val byName = bundle.files.associateBy { it.name }
		require(byName.size == bundle.files.size) { "MOC3 文件族中存在重名文件" }
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
		require(missing.isEmpty()) { "model3.json 引用了缺失文件：${missing.joinToString()}" }
		manifest.fileReferences.displayInfo?.let { Moc3.readCdi3(byName.getValue(it).bytes.decodeToString()) }
		manifest.fileReferences.physics?.let { Moc3.readPhysics3(byName.getValue(it).bytes.decodeToString()) }
		manifest.fileReferences.motions.orEmpty().values.flatten().forEach {
			Json.parseToJsonElement(byName.getValue(it.file).bytes.decodeToString())
		}
	}

	private fun validateRigShape(label: String, expected: PuppetModel, actual: PuppetModel) {
		fun <T> requireSame(kind: String, left: Set<T>, right: Set<T>) {
			require(left == right) {
				"$label 回读后的 $kind 不一致；缺少=${left - right}，多出=${right - left}"
			}
		}
		requireSame("参数", expected.parameters.map { it.id.raw }.toSet(), actual.parameters.map { it.id.raw }.toSet())
		requireSame("变形器", expected.deformers.map { it.id.raw }.toSet(), actual.deformers.map { it.id.raw }.toSet())
		requireSame("画元", expected.drawables.map { it.id.raw }.toSet(), actual.drawables.map { it.id.raw }.toSet())
	}

	private fun writeContained(root: Path, relativeName: String, bytes: ByteArray): ExportedFile {
		val target = root.resolve(relativeName.replace('/', java.io.File.separatorChar)).normalize()
		require(target.startsWith(root)) { "输出路径越界：$relativeName" }
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
				"$format：未保留原始 PSD 源图编辑链；已从 ${notice.pageCount} 页贴图重建可编辑图层（模型/Rig 数据仍保留）"
			else -> "$format：$notice"
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
			"    {\"source\":${quote(layer.source.name)},\"tag\":${quote(layer.semantic.tag.canonicalName)},\"side\":${quote(layer.semantic.side.name)},\"drawable\":${quote(rig.puppet.drawables.firstOrNull { it.name == layer.source.name }?.id?.raw ?: "")}}"
		}
		val hasFrontHair = analysis.layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR && it.opaquePixels > 0 }
		val hasBackHair = analysis.layers.any { it.semantic.tag == SemanticTag.BACK_HAIR && it.opaquePixels > 0 }
		return """
		{
		  "version": 1,
		  "model": ${quote(baseName)},
		  "generator": "AutoLive2D 0.1.0",
		  "runtimeTarget": ${quote(rig.puppet.runtimeTarget.name)},
		  "mocVersion": ${rig.puppet.runtimeTarget.mocVersion().byteValue},
		  "canvas": {"width":${analysis.source.widthPx},"height":${analysis.source.heightPx}},
		  "config": {"atlasSize":${config.atlasSize},"meshSpacing":${config.meshSpacing},"headTurnStrength":${config.headTurnStrength},"bodyStrength":${config.bodyStrength}},
		  "faceRig": {"algorithm":"perspective-parallelogram-nine-pose-v2","angleX":[-45,0,45],"angleY":[-30,0,30],"centerX":${rig.faceCenterX},"centerY":${rig.faceCenterY},"radiusX":${rig.faceRadiusX},"radiusY":${rig.faceRadiusY}},
		  "deformerHierarchy": {"head":"DeformHeadContainer","face":"DeformFaceNinePose","frontHair":["DeformHairFrontFollow","DeformHairFrontPhysics"],"backHair":["DeformHairBackFollow","DeformHairBackPhysics"]},
		  "physics": {"enabled":${config.generatePhysics},"frontHair":$hasFrontHair,"backHair":$hasBackHair,"preset":"stretchystudio-hiyori-pendulum"},
		  "summary": {"layers":${analysis.layers.size},"drawables":${rig.puppet.drawables.size},"deformers":${rig.puppet.deformers.size},"parameters":${rig.puppet.parameters.size},"atlasPages":${atlas.pages.size}},
		  "layers": [
		$layers
		  ],
		  "warnings": [${warnings.joinToString(",") { quote(it) }}]
		}
		""".trimIndent()
	}
}
