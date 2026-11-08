package io.github.psd2live.application

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.ProjectRepository
import java.nio.file.Path

/** Application orchestration for CLI/tool inputs; the core exporter only consumes a prepared model. */
internal object WorkspaceModelLoader {
    suspend fun load(path: Path): RigPreviewModel = when {
        path.fileName.toString().endsWith(".psd2live", true) -> ProjectRepository().open(path).use { opened ->
            WorkspacePreviewBuilder().build(opened.history.head().snapshot)
        }
        path.fileName.toString().endsWith(".psd", true) -> PSD2LivePipeline().let { it.buildSourceExport(it.readPsd(path)) }
        else -> throw IllegalArgumentException("Export input must be a .psd2live project or a .psd file: $path")
    }
}
