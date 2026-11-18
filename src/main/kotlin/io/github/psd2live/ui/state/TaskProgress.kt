package io.github.psd2live.ui.state

import io.github.psd2live.core.DownloadState
import io.github.psd2live.i18n.tr

/** The single task indicator displayed in the bottom status bar. */
internal data class TaskProgress(
    val text: String,
    val fraction: Float? = null,
    val canCancelBake: Boolean = false,
)

internal fun taskProgress(
    state: PSD2LiveState,
    baking: PSD2LiveViewModel.SimulationBaking?,
    download: DownloadState,
    project: PSD2LiveViewModel.ProjectProgress? = null,
    guide: TaskProgress? = null,
): TaskProgress? {
    // Baking may run inside another workspace operation; prefer its detailed progress.
    if (baking != null) {
        val text = if (baking.count > 1) {
            tr("sim.bakingBatch", baking.id, baking.index + 1, baking.count)
        } else tr("sim.bakingOne", baking.id)
        return TaskProgress(text, baking.overall.coerceIn(0f, 1f), canCancelBake = true)
    }
    when (download) {
        is DownloadState.Downloading -> return TaskProgress(
            (if (download.currentItem == "nunif") tr("upscale.downloadingNunif") else tr("upscale.downloadingModel")) +
                " · ${io.github.psd2live.core.ModelDownloader.formatBytes(download.bytesDownloaded)} / " +
                "${io.github.psd2live.core.ModelDownloader.formatBytes(download.totalBytes)} " +
                "(${io.github.psd2live.core.ModelDownloader.formatSpeed(download.speedBytesPerSec)})",
            download.progress.coerceIn(0f, 1f),
        )
        DownloadState.Extracting -> return TaskProgress(tr("upscale.extracting"))
        DownloadState.Verifying -> return TaskProgress(tr("upscale.verifying"))
        else -> Unit
    }
    if (project != null) return TaskProgress(project.text, project.fraction)
    return when {
        state.isBusy || state.isExportingPsd -> TaskProgress(
            state.statusText,
            if (state.isIndeterminateProgress) null else state.progress.coerceIn(0f, 1f),
        )
        state.canvasEditBusy -> TaskProgress(state.statusText)
        else -> guide
    }
}
