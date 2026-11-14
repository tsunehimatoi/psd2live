package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import org.umamo.format.art.SourceArt
import java.nio.file.Path

/** Source export is an application use case: it selects the CLI preset before target export. */
fun PSD2LivePipeline.run(
	psd: Path,
	outputDirectory: Path,
	config: PipelineConfig = PipelineConfig(),
	progress: ProgressListener = ProgressListener { _, _ -> },
): PipelineResult {
	progress.update(tr("progress.readPsd"), 0.04)
	return exportSource(readPsd(psd), psd.fileName.toString(), outputDirectory, config, progress)
}

/** Export the current authoritative source, including Agent-created layers, without rereading the PSD. */
fun PSD2LivePipeline.run(
	source: SourceArt,
	sourceName: String,
	outputDirectory: Path,
	config: PipelineConfig = PipelineConfig(),
	progress: ProgressListener = ProgressListener { _, _ -> },
): PipelineResult {
	progress.update(tr("progress.readPsd"), 0.04)
	return exportSource(source, sourceName, outputDirectory, config, progress)
}

private fun PSD2LivePipeline.exportSource(source: SourceArt, name: String, directory: Path, config: PipelineConfig,
                         progress: ProgressListener): PipelineResult {
	return export(buildSourceExport(source, config, building(progress)), name, directory, progress)
}

internal fun PSD2LivePipeline.buildSourceExport(source: SourceArt, config: PipelineConfig = PipelineConfig(),
                               progress: ProgressListener = ProgressListener { _, _ -> }): RigPreviewModel = when {
	!isStandaloneSource(config) -> buildPreview(source, config, progress)
	config.meshOnly -> buildRawMeshExport(source, config, progress)
	!config.generateDeformers -> buildRawWithoutDeformers(source, config, progress)
	else -> CliPresetBindings.build(this, source, config, progress)
}

/** The build's own progress, in the part of an export before the model is written. */
private fun building(progress: ProgressListener) = ProgressListener { stage, fraction -> progress.update(stage, 0.04 + fraction * 0.54) }


private fun isStandaloneSource(config: PipelineConfig): Boolean =
    config.rigEdits == RigEditOverlay.Empty && config.generationSource == null && config.meshSource == null &&
        !config.deferRig && config.rigPendingLayerIds.isEmpty()
