package io.github.psd2live.core

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.ExportRegistry
import io.github.psd2live.format.compile.ExportReport
import io.github.psd2live.format.compile.ExportTarget
import io.github.psd2live.targets.cubism.Cmo3Target
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.targets.raster.RasterTargets
import java.nio.file.Files
import java.nio.file.Path
import io.github.psd2live.format.compile.GeometryEvaluator
import io.github.psd2live.format.eval.NativeGeometryEvaluator
import io.github.psd2live.format.eval.P2lRuntime

/** Every export target this build offers, compiled from one rig through the neutral IR. */
internal object ExportService {
	/**
	 * Geometry for targets that bake deformation: the Rust runtime when its library is present (faster, and
	 * pose for pose the editor's evaluation), else the editor's evaluator.
	 */
	val geometry: GeometryEvaluator by lazy {
		runCatching { P2lRuntime.load()?.let(::NativeGeometryEvaluator) }.getOrNull() ?: IrGeometryEvaluator
	}

	/**
	 * Skeletal-animation targets that bake parameters into additive animations. Their combined poses still differ
	 * visibly from the editor, so the editor's menu leaves them out; the command line marks them experimental.
	 * Unity stays in the menu: its importer installs itself once, so more than one character can be exported.
	 */
	val experimental: Set<String> = setOf("spine", "dragonbones", "gltf")

	fun registry(config: PipelineConfig): ExportRegistry = ExportRegistry(listOf(
		Moc3Target,
		Cmo3Target { BezierWarp.configureEditor(it, config.rigEdits) },
		io.github.psd2live.targets.cubism.VTubeStudioTarget,
		io.github.psd2live.targets.psd.PosedPsdTarget(IrFrameRenderer),
		io.github.psd2live.targets.spine.SpineTarget(geometry),
		io.github.psd2live.targets.dragonbones.DragonBonesTarget(geometry),
		io.github.psd2live.targets.runtime.P2lrtTarget,
		io.github.psd2live.targets.web.WebTarget,
		io.github.psd2live.targets.gltf.GltfTarget(geometry),
		io.github.psd2live.targets.unity.UnityTarget(geometry),
	) + RasterTargets(IrFrameRenderer).all)

	/** Built-in options for [target] from the document's export settings; explicit [settings] win. */
	fun options(target: ExportTarget, baseName: String, config: PipelineConfig, settings: Map<String, String> = emptyMap()): ExportOptions {
		val defaults = when (target.id) {
			Moc3Target.id, io.github.psd2live.targets.cubism.VTubeStudioTarget.id -> PSD2LivePipeline().moc3ExportOptions(baseName, config).settings
			else -> emptyMap()
		}
		return ExportOptions(baseName, settings = defaults + settings)
	}

	/**
	 * Exports [preview] with [target] into [directory] and writes the loss report beside the files as
	 * `<baseName>.<target>.report.json`. Files are staged and moved in only after the export succeeds.
	 */
	fun export(preview: RigPreviewModel, target: ExportTarget, options: ExportOptions, directory: Path): ExportReport {
		val root = directory.toAbsolutePath().normalize()
		Files.createDirectories(root)
		val stage = Files.createTempDirectory(root, ".export-")
		try {
			val report = writeStaged(preview, target, options, stage)
			ExportPublication.commit(root, stage, report.files, reportName(options, target), report.toJson())
			return report
		} finally {
			stage.toFile().deleteRecursively()
		}
	}

	/** Writes [target]'s files into [stage] and returns the report. The caller publishes them. */
	internal fun writeStaged(preview: RigPreviewModel, target: ExportTarget, options: ExportOptions, stage: Path): ExportReport {
		val ir = RigIrCompiler.compile(preview, tileArt = target.id == "cmo3", simulations = target.id == "p2lrt" || target.id == "web")
		val root = stage.toAbsolutePath().normalize()
		return Compiler.export(target, ir, options) { path, bytes ->
			val file = root.resolve(path).normalize()
			require(file.startsWith(root)) { "Export path escapes the output: $path" }
			Files.createDirectories(file.parent)
			Files.write(file, bytes)
		}
	}

	internal fun reportName(options: ExportOptions, target: ExportTarget) = "${options.baseName}.${target.id}.report.json"

}
