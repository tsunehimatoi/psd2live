package io.github.psd2live.tools

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.SkeletonAutoBuilder
import java.io.File
import kotlin.test.Test

/**
 * SHA-256 of every exported file and of the preview bundle for the bundled samples - plain, on their auto skeleton,
 * with an authored clip, and the plain cmo3 imported back as a new project - so a refactor of the export path can be
 * checked byte for byte against the previous commit.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ExportGoldenTool'
 * PSD2LIVE_GOLDEN_LABEL names the output (current by default); PSD2LIVE_GOLDEN_RECIPE=legacy builds the samples with the
 * legacy rig recipe of a project saved before rig modules. Writes build/tools/export-golden/<label>.txt and the
 * exports under <label>/<sample>-<variant>/. A cmo3 holds random editor GUIDs, so it is listed by [cmo3Readback].
 */
class ExportGoldenTool {
	@Test fun dump() {
		requireTools()
		val out = output("export-golden")
		val label = setting("PSD2LIVE_GOLDEN_LABEL", "current")
		// The recipe of a project saved before rig modules, which must export as it did.
		val legacy = setting("PSD2LIVE_GOLDEN_RECIPE", "new") == "legacy"
		val report = StringBuilder()
		for (sample in listOf("tml", "ds")) {
			val psd = File("examples/$sample/psd-input/$sample.psd").toPath()
			val plain = PSD2LivePipeline().buildPreview(psd, if (legacy) PipelineConfig(recipe = io.github.psd2live.core.recipe.RigRecipe.Legacy) else PipelineConfig())
			val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
			val wave = MotionClip("wave", "Wave", duration = 1.5f, curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0.2f, 0f),
				MotionKey(0.7f, 20f, MotionInterpolation.LINEAR), MotionKey(1.1f, -5f, MotionInterpolation.STEPPED), MotionKey(1.5f, -10f)))))
			val authored = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(motionClips = listOf(wave))))
			for ((variant, preview) in listOf("plain" to plain, "skeleton" to skeletal, "clip" to authored)) {
				val dir = File(out, "$label/$sample-$variant").apply { deleteRecursively(); mkdirs() }
				val result = PSD2LivePipeline().run(preview.analysis.source, sample, dir.toPath(), preview.config.copy(exportJson = false))
				for (file in result.exportedFiles.sortedBy { it.path.toString() }) {
					val bytes = file.path.toFile().readBytes()
					val name = dir.toPath().toAbsolutePath().relativize(file.path.toAbsolutePath()).toString().replace('\\', '/')
					val digest = if (name.endsWith(".cmo3")) "readback=" + cmo3Readback(bytes) else sha256(bytes)
					report.appendLine("$sample-$variant $name $digest")
				}
				for (asset in preview.runtimeBundle.assets.sortedBy { it.path }) report.appendLine("$sample-$variant preview:${asset.path} ${sha256(asset.bytes)}")
			}
			// An imported CMO3 goes through its own import path: its atlas is packed again from the layers it brings.
			val plainCmo3 = File(out, "$label/$sample-plain/$sample.cmo3").readBytes()
			val (incoming, settings) = io.github.psd2live.core.Cmo3ModelImport.prepare(plainCmo3, io.github.psd2live.core.Cmo3ImportMode.NEW, null,
				plain.config.copy(exportJson = false))
			val dir = File(out, "$label/$sample-imported").apply { deleteRecursively(); mkdirs() }
			val imported = PSD2LivePipeline().run(incoming, sample, dir.toPath(), settings.copy(exportJson = false))
			for (file in imported.exportedFiles.sortedBy { it.path.toString() }) {
				val bytes = file.path.toFile().readBytes()
				val name = dir.toPath().toAbsolutePath().relativize(file.path.toAbsolutePath()).toString().replace('\\', '/')
				report.appendLine("$sample-imported $name ${if (name.endsWith(".cmo3")) "readback=" + cmo3Readback(bytes) else sha256(bytes)}")
			}
			for (asset in imported.previewModel.runtimeBundle.assets.sortedBy { it.path }) report.appendLine("$sample-imported preview:${asset.path} ${sha256(asset.bytes)}")
		}
		File(out, "$label.txt").writeText(report.toString())
		println(report)
	}
}
