package io.github.psd2live.tools

import io.github.psd2live.application.run

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.scenario.Characters
import io.github.psd2live.scenario.Studio
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * [ExportGoldenTool] on the synthetic characters the scenarios use, so the export of a change can be compared byte for
 * byte with the previous commit without downloading the example catalog: the pipeline export (plain, auto skeleton,
 * authored clip) and the workspace path a PSD import takes (import, build, export through the export session).
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*LayoutGoldenTool'
 * PSD2LIVE_GOLDEN_LABEL names the output (current by default): build/tools/layout-golden/<label>.txt.
 */
class LayoutGoldenTool {
	@Test fun dump() {
		requireTools()
		val out = output("layout-golden")
		val label = setting("PSD2LIVE_GOLDEN_LABEL", "current")
		val work = File(out, "$label-work").absoluteFile.apply { deleteRecursively(); mkdirs() }
		val report = StringBuilder()
		val samples = listOf("figure" to Characters.figurePsd(work.toPath()), "mouth" to Characters.mouthPsd(work.toPath()))
		val config = PipelineConfig(atlasSize = 512, generatePhysics = false)
		for ((sample, psd) in samples) {
			val plain = PSD2LivePipeline().buildPreview(psd, config)
			val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
			val wave = MotionClip("wave", "Wave", duration = 1.5f, curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0.2f, 0f),
				MotionKey(0.7f, 20f, MotionInterpolation.LINEAR), MotionKey(1.1f, -5f, MotionInterpolation.STEPPED), MotionKey(1.5f, -10f)))))
			val authored = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(motionClips = listOf(wave))))
			for ((variant, preview) in listOf("plain" to plain, "skeleton" to skeletal, "clip" to authored)) {
				val dir = File(out, "$label/$sample-$variant").apply { deleteRecursively(); mkdirs() }
				val result = PSD2LivePipeline().run(preview.analysis.source, sample, dir.toPath(), preview.config.copy(exportJson = false))
				for (file in result.exportedFiles.sortedBy { it.path.toString() }) report.appendLine("$sample-$variant ${entry(dir, file.path.toFile())}")
				for (asset in preview.runtimeBundle.assets.sortedBy { it.path }) report.appendLine("$sample-$variant preview:${asset.path} ${sha256(asset.bytes)}")
			}
			// The workspace import path: the project a PSD import installs, exported as the editor exports it.
			val temp = Files.createTempDirectory(work.toPath(), "studio")
			Studio(temp, emptySet()).use { studio ->
				studio.importPsd(psd, config)
				for (target in listOf("moc3", "cmo3")) {
					val files = studio.export(target).sortedBy { it.fileName.toString() }
					for (file in files) report.appendLine("$sample-import-$target ${entry(file.parent.toFile(), file.toFile())}")
				}
				for (asset in studio.now.model.runtimeBundle.assets.sortedBy { it.path }) report.appendLine("$sample-import preview:${asset.path} ${sha256(asset.bytes)}")
			}
		}
		File(out, "$label.txt").writeText(report.toString())
		println("LAYOUT GOLDEN written: ${File(out, "$label.txt").absolutePath}")
	}

	private fun entry(dir: File, file: File): String {
		val bytes = file.readBytes()
		val name = dir.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString().replace('\\', '/')
		return "$name ${if (name.endsWith(".cmo3")) "readback=" + cmo3Readback(bytes) else sha256(bytes)}"
	}
}
