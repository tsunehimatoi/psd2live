package io.github.psd2live.ui.views

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.psd2live.application.TurnStyleCandidates
import io.github.psd2live.application.WorkspaceConflict
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceTurnStylePreview
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.ModalPanel
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.toImageBitmapFast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TurnStyleExploreButton(
    enabled: Boolean,
    vm: PSD2LiveViewModel,
    operation: (Map<String, Float>) -> WorkspaceDocumentOperation,
) {
    var preview by remember { mutableStateOf<WorkspaceTurnStylePreview?>(null) }
    Box(Modifier.fillMaxWidth().padding(start = 18.dp, bottom = 3.dp), contentAlignment = Alignment.CenterEnd) {
        CompactButton(tr("turnExplorer.open"), { preview = vm.openTurnStylePreview() }, enabled = enabled, height = 22.dp)
    }
    preview?.let { session ->
        TurnStyleExplorer(session, operation, onDismiss = { preview = null }) { values, done ->
            vm.saveDocumentEdits(session.state, tr("turnExplorer.history"), listOf(operation(values)), done)
        }
    }
}

private data class TurnStyleTile(val current: ImageBitmap, val turned: ImageBitmap)
private data class TurnStyleAngle(val x: Float, val y: Float, val image: ImageBitmap)
private sealed interface TurnStyleRenderRequest {
    data class Batch(val options: List<TurnStyleCandidates.Option>) : TurnStyleRenderRequest
    data class Angles(val option: TurnStyleCandidates.Option) : TurnStyleRenderRequest
}

@Composable
private fun TurnStyleExplorer(
    preview: WorkspaceTurnStylePreview,
    operation: (Map<String, Float>) -> WorkspaceDocumentOperation,
    onDismiss: () -> Unit,
    onApply: (Map<String, Float>, (String?) -> Unit) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    var batch by remember(preview) { mutableStateOf(TurnStyleCandidates.randomBatch()) }
    var tiles by remember(preview) { mutableStateOf(emptyMap<Map<String, Float>, TurnStyleTile>()) }
    var failed by remember(preview) { mutableStateOf(emptySet<Map<String, Float>>()) }
    var selected by remember(preview) { mutableStateOf<TurnStyleCandidates.Option?>(null) }
    var details by remember(preview) { mutableStateOf(false) }
    var angles by remember(preview) { mutableStateOf(emptyMap<Int, TurnStyleAngle>()) }
    var turned by remember(preview) { mutableStateOf(false) }
    var applying by remember(preview) { mutableStateOf(false) }
    var failure by remember(preview) { mutableStateOf<String?>(null) }
    var detailFailure by remember(preview) { mutableStateOf<String?>(null) }
    var stale by remember(preview) { mutableStateOf(false) }
    val makeOperation by rememberUpdatedState(operation)

    fun load(next: List<TurnStyleCandidates.Option>, anchor: TurnStyleCandidates.Option? = null) {
        tiles = anchor?.let { keep -> tiles[keep.values]?.let { mapOf(keep.values to it) } }.orEmpty()
        selected = anchor
        failed = emptySet()
        failure = null
        batch = next
    }

    suspend fun renderBatch(request: List<TurnStyleCandidates.Option>) {
        try {
            coroutineScope {
                val pending = request.filter { it.values !in tiles && it.values !in failed }
                for (option in pending) launch {
                    val values = option.values
                    try {
                        val rendered = preview.thumbnail(makeOperation(values))
                        val tile = withContext(Dispatchers.Default) {
                            TurnStyleTile(rendered.current.toImageBitmapFast(), rendered.turned.toImageBitmapFast())
                        }
                        if (batch === request && !details && !applying) tiles = tiles + (values to tile)
                    } catch (error: Exception) {
                        if (error is CancellationException || error is WorkspaceConflict) throw error
                        if (batch === request && !details && !applying) {
                            failed = failed + values
                            if (failure == null) failure = error.message ?: error.javaClass.simpleName
                        }
                    }
                }
            }
        } catch (_: WorkspaceConflict) {
            if (batch === request && !details && !applying) stale = true
        }
    }

    suspend fun renderAngles(option: TurnStyleCandidates.Option) {
        if (angles.size == WorkspaceTurnStylePreview.ANGLE_COUNT || detailFailure != null) return
        try {
            preview.angles(makeOperation(option.values)) { index, frame ->
                val angle = withContext(Dispatchers.Default) { TurnStyleAngle(frame.x, frame.y, frame.image.toImageBitmapFast()) }
                if (details && selected == option && !applying) angles = angles + (index to angle)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: WorkspaceConflict) {
            stale = true
        } catch (error: Exception) {
            detailFailure = error.message ?: error.javaClass.simpleName
        }
    }

    LaunchedEffect(preview) {
        // Let cancellation finish before starting another batch that may use the same plugin worker.
        snapshotFlow {
            when {
                stale || applying -> null
                details -> selected?.let { TurnStyleRenderRequest.Angles(it) }
                else -> TurnStyleRenderRequest.Batch(batch)
            }
        }.collectLatest { request ->
            when (request) {
                is TurnStyleRenderRequest.Batch -> renderBatch(request.options)
                is TurnStyleRenderRequest.Angles -> renderAngles(request.option)
                null -> Unit
            }
        }
    }

    val loading = !stale && if (details) angles.size < WorkspaceTurnStylePreview.ANGLE_COUNT && detailFailure == null
        else tiles.size + failed.size < batch.size
    val chosen = selected
    Dialog(onDismissRequest = { if (!applying) onDismiss() }) {
        ModalPanel(
            modifier = Modifier.width(640.dp).heightIn(max = 700.dp),
            title = tr(if (details) "turnExplorer.angles" else "turnExplorer.open"),
            onDismiss = onDismiss,
            dismissible = !applying,
            footer = {
                CompactButton(tr("turnExplorer.apply"), {
                    if (chosen != null && !applying && !stale) {
                        applying = true
                        failure = null
                        onApply(chosen.values) { error ->
                            applying = false
                            if (error == null) onDismiss() else failure = error
                        }
                    }
                }, enabled = chosen != null && !applying && !stale, isPrimary = true)
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (details) {
                    CompactButton(tr("turnExplorer.back"), { details = false }, enabled = !applying)
                } else {
                    CompactButton(tr("turnExplorer.next"), { load(TurnStyleCandidates.randomBatch()) }, enabled = !applying && !stale)
                    CompactButton(tr("turnExplorer.nearby"), {
                        chosen?.let { load(TurnStyleCandidates.nearbyBatch(it), it.copy(distance = TurnStyleCandidates.Distance.ANCHOR)) }
                    }, enabled = chosen != null && !applying && !stale)
                    CompactButton(tr("turnExplorer.angles"), {
                        angles = emptyMap()
                        detailFailure = null
                        details = true
                    }, enabled = chosen != null && !applying && !stale)
                    Spacer(Modifier.weight(1f))
                    CompactButton(tr(if (turned) "turnExplorer.currentPose" else "turnExplorer.turnPose"), { turned = !turned })
                }
            }
            Box(Modifier.heightIn(min = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (loading || applying) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(13.dp), color = colors.accent, strokeWidth = 2.dp)
                    val status = when {
                        applying -> tr("turnExplorer.applying")
                        details -> tr("turnExplorer.renderingAngles")
                        else -> tr("turnExplorer.progress", tiles.size + failed.size, batch.size)
                    }
                    Text(status, color = colors.textMuted, style = typography.caption)
                }
            }
            if (stale) Text(tr("turnExplorer.stale"), color = colors.error, style = typography.caption)
            (if (details) detailFailure ?: failure else failure)?.let {
                Text(it, color = colors.error, style = typography.caption, maxLines = 3)
            }
            for (row in 0..2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0..2) {
                    val index = row * 3 + column
                    if (details) {
                        val angle = angles[index]
                        TurnStyleCell(Modifier.weight(1f), angle?.image,
                            angle?.let { tr("turnExplorer.angle", it.x, it.y) })
                    } else {
                        val option = batch[index]
                        val tile = tiles[option.values]
                        val caption = if (option.values in failed) tr("turnExplorer.failed") else option.distance?.let {
                            tr("turnExplorer.distance.${it.name.lowercase()}")
                        }
                        val description = tr("turnExplorer.option", index + 1)
                        key(option.values) {
                            TurnStyleCell(Modifier.weight(1f).semantics { contentDescription = description },
                                if (turned) tile?.turned else tile?.current, caption, selected = option == chosen,
                                animate = option.distance != null && option.distance != TurnStyleCandidates.Distance.ANCHOR,
                                onClick = { selected = option }, enabled = tile != null && !applying && !stale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnStyleCell(
    modifier: Modifier,
    image: ImageBitmap?,
    caption: String?,
    selected: Boolean = false,
    animate: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = LocalToolColors.current
    val shape = RoundedCornerShape(5.dp)
    val selection = if (onClick == null) Modifier else Modifier.selectable(selected, enabled, Role.Button, onClick = onClick)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
    val reveal by animateFloatAsState(if (animate && image == null) 0f else 1f, tween(240))
    Box(modifier.aspectRatio(4f / 3f).clip(shape).background(colors.checkerDark)
        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border, shape).then(selection),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize().graphicsLayer {
            alpha = reveal
            scaleX = 0.94f + reveal * 0.06f
            scaleY = scaleX
        })
        if (caption != null) Text(caption, color = colors.textPrimary, style = LocalToolTypography.current.caption,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                .background(colors.panelElevated, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
    }
}
