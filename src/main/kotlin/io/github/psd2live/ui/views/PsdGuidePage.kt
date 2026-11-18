package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.PsdGuideBindings
import io.github.psd2live.application.TurnStyleCandidates
import io.github.psd2live.application.WorkspaceModelPresetLibrary
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import io.github.psd2live.ui.ParameterKeyMarks
import java.awt.image.BufferedImage

private val PsdGuideStep.label get() = "psdGuide.step.${name.lowercase()}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PsdGuidePage(viewModel: PSD2LiveViewModel) {
    val state = viewModel.psdGuide
    val path = state.path ?: return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val step = state.step
    val source = state.source
    val ready = !state.busy && state.bindings != null
    val canAdvance = ready && (step != PsdGuideStep.PRESET || state.preset != null)
    val previous = PsdGuideStep.entries.getOrNull(step.ordinal - 1)
    val next = PsdGuideStep.entries.getOrNull(step.ordinal + 1)

    Box(Modifier.fillMaxSize().background(colors.panelBackground)) {
        Column(Modifier.fillMaxSize().padding(Spacing.Margin), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            Text(path.fileName.toString(), color = colors.textPrimary, style = typography.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                PsdGuideStep.entries.forEach { target ->
                    CompactButton(tr(target.label), { state.go(target) }, enabled = ready && target <= state.reached,
                        isPrimary = target == step, height = 24.dp)
                }
            }
            state.error?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, modifier = Modifier.weight(1f), color = colors.error, style = typography.caption, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                    if (!state.busy && state.canRetry) {
                        CompactButton(tr("psdGuide.retry"), state::retry)
                    }
                }
            }
            if (source == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.busy) GuideProgress(tr("psdGuide.reading"))
                }
            } else {
                key(path, step) {
                    when (step) {
                        PsdGuideStep.SPLIT -> GuideColumns(Modifier.weight(1f), preview = {
                            SplitGuidePreview(state, source)
                        }, controls = { SplitGuideControls(state) })
                        PsdGuideStep.BINDING -> GuideColumns(Modifier.weight(1f), preview = {
                            val layers = state.bindings?.layers.orEmpty()
                            GuideCanvas(source.model.analysis.preview,
                                layers.filter { it.source.id.raw in state.bindingFocus }.map { it.bounds })
                        }, controls = { BindingGuideControls(state) })
                        PsdGuideStep.PRESET -> GuideColumns(Modifier.weight(1f), preview = {
                            GuideCanvas(state.presetImage)
                        }, controls = {
                            val entries by WorkspaceModelPresetLibrary.shared.state.collectAsState()
                            ModifierPresetControls(state.selection, entries, state.preset?.model ?: source.model,
                                !state.busy, state::selectPreset)
                        })
                        PsdGuideStep.FACE -> FaceGuide(state, Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(colors.panelElevated).padding(Spacing.Margin),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalAlignment = Alignment.CenterVertically) {
                if (state.busy) GuideProgress(tr(if (state.saving) "psdGuide.creating" else "psdGuide.working"))
                Spacer(Modifier.weight(1f))
                if (previous != null) CompactButton(tr("psdGuide.back"), { state.go(previous) }, enabled = ready)
                if (next != null) CompactButton(tr("psdGuide.next"), { state.go(next) }, enabled = canAdvance, isPrimary = true)
                else CompactButton(tr("psdGuide.create"), viewModel::createPsdGuideProject,
                    enabled = ready && state.preset != null, isPrimary = true)
            }
        }
        if (state.confirmDiscard) {
            val cancel = { state.confirmDiscard = false }
            ModalDialogFrame(title = tr("psdGuide.discard.title"), onDismiss = cancel, footer = {
                CompactButton(tr("project.cancel"), cancel)
                CompactButton(tr("project.discard"), viewModel::discardPsdGuide, isPrimary = true)
            }) {
                Text(tr("psdGuide.discard.message"), color = colors.textPrimary, style = typography.body)
            }
        }
    }
}

@Composable
private fun GuideColumns(modifier: Modifier, preview: @Composable () -> Unit, controls: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 660.dp) {
            val previewHeight = minOf(maxWidth * 0.75f, 360.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                Box(Modifier.fillMaxWidth().height(previewHeight)) { preview() }
                GuideControls(Modifier.fillMaxWidth(), controls)
            }
        } else {
            val sidebarWidth = (maxWidth * 0.38f).coerceIn(312.dp, 500.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { preview() }
                GuideControls(Modifier.width(sidebarWidth).fillMaxHeight().verticalScroll(rememberScrollState()), controls)
            }
        }
    }
}

@Composable
private fun GuideControls(modifier: Modifier, controls: @Composable ColumnScope.() -> Unit) {
    val colors = LocalToolColors.current
    Column(modifier.clip(RoundedCornerShape(Corners.Panel))
        .background(colors.panelElevated).padding(Spacing.Margin),
        verticalArrangement = Arrangement.spacedBy(Spacing.Gap), content = controls)
}

@Composable
private fun GuideProgress(text: String) {
    val colors = LocalToolColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        CircularProgressIndicator(Modifier.size(13.dp), color = colors.accent, strokeWidth = 2.dp)
        Text(text, color = colors.textMuted, style = LocalToolTypography.current.caption, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GuideNote(text: String) {
    Text(text, style = LocalToolTypography.current.body, color = LocalToolColors.current.textMuted)
}

@Composable
private fun GuideCanvas(image: BufferedImage?, bounds: List<Bounds> = emptyList()) {
    val bitmap = remember(image) { image?.toImageBitmapFast() }
    val colors = LocalToolColors.current
    Box(Modifier.fillMaxSize().background(colors.inputBackground), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (bounds.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val origin = Offset((size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
                bounds.forEach { region ->
                    val start = origin + Offset(region.left * scale, region.top * scale)
                    val area = Size(region.width * scale, region.height * scale)
                    drawRect(colors.accent.copy(alpha = 0.2f), start, area)
                    drawRect(colors.accent, start, area, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun SplitGuidePreview(state: PsdGuideState, source: WorkspacePsdGuide.Candidate) {
    val selected = state.splitItems.firstOrNull { it.offer.layerId == state.splitFocus }
    if (selected == null) {
        GuideCanvas(source.model.analysis.preview)
        return
    }
    val layer = source.model.analysis.layers.first { it.source.id.raw == selected.offer.layerId }
    val before by produceState<BufferedImage?>(null, layer) {
        value = withContext(Dispatchers.Default) {
            val raster = layer.source.raster
            PreviewRenderer.rasterImage(raster.width, raster.height, raster.rgba)
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        GuideNote(tr("psdGuide.split.before"))
        Box(Modifier.weight(1f)) {
            val original = before
            if (original != null) GuideCanvas(original) else GuideCanvas(source.model.analysis.preview, listOf(layer.bounds))
        }
        GuideNote(tr("psdGuide.split.after"))
        val previews = selected.offer.plan.previewImages
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tileWidth = ((maxWidth - Spacing.Gap * (previews.size - 1)) / previews.size).coerceAtLeast(96.dp)
            Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                previews.forEach { image -> Box(Modifier.width(tileWidth).fillMaxHeight()) { GuideCanvas(image) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitGuideControls(state: PsdGuideState) {
    val items = state.splitItems
    val chosen = items.filter { it.isSelected }
    val enabled = !state.busy
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        if (!state.splitScanned) CompactButton(tr("psdGuide.split.scan"), state::scan, enabled = enabled)
        if (state.source !== state.guide?.base) CompactButton(tr("psdGuide.split.reset"), state::resetSplits, enabled = enabled)
        if (items.isNotEmpty()) {
            CompactButton(tr("editor.meshSplit.confirmSelected", chosen.size), { state.split(items) },
                enabled = enabled && chosen.isNotEmpty() && chosen.all { it.isValid }, isPrimary = true)
            CompactButton(tr("editor.meshSplit.keepAll"), { state.go(PsdGuideStep.BINDING) }, enabled = enabled)
        }
    }
    when {
        !state.splitScanned -> GuideNote(tr("psdGuide.split.optional"))
        items.isEmpty() -> GuideNote(tr("psdGuide.split.none"))
        else -> {
            SplitHeader(items, enabled)
            items.forEach { item ->
                key(item.offer.layerId) {
                    SplitCard(item, emptyList(), enabled, onFocus = { state.splitFocus = item.offer.layerId },
                        focused = state.splitFocus == item.offer.layerId)
                }
            }
        }
    }
}

@Composable
private fun BindingGuideControls(state: PsdGuideState) {
    val report = state.bindings ?: return
    val layers = report.layers.associateBy { it.source.id.raw }
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Text(tr("psdGuide.binding.todo", report.findings.size), color = colors.textPrimary, style = typography.body)
    if (report.findings.isEmpty()) GuideNote(tr("psdGuide.binding.clean"))
    report.findings.forEach { finding ->
        val names = finding.layers.mapNotNull { layers[it]?.source?.name }.joinToString(" / ")
        val reason = tr("psdGuide.binding.${finding.kind.name.lowercase()}", partLabel(finding.tag))
        if (finding.layers.isEmpty()) GuideNote(reason)
        else {
            val selected = state.bindingFocus == finding.layers
            CompactButton("$names · $reason", { state.bindingFocus = finding.layers }, enabled = !state.busy,
                isPrimary = selected, modifier = Modifier.fillMaxWidth())
            if (selected) GuideNote(reason)
        }
    }
    Text(tr("psdGuide.binding.auto", report.automatic.size), color = colors.textPrimary, style = typography.body)
    report.automatic.forEach { (id, value) ->
        val layer = layers.getValue(id)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalAlignment = Alignment.CenterVertically) {
            CompactButton("${layer.source.name} · ${sideLabel(value.side)}", { state.bindingFocus = listOf(id) },
                modifier = Modifier.weight(1f))
            CompactButton(tr("psdGuide.binding.undo"), { state.classify(id, PsdGuideBindings.classification(layer)) },
                enabled = !state.busy)
        }
    }
    val options = listOf("") + report.layers.map { it.source.id.raw }
    if (options.size > 1) CompactSelect(options, state.bindingFocus.firstOrNull().orEmpty(),
        label = { layers[it]?.source?.name ?: tr("psdGuide.binding.layer") },
        onSelect = { state.bindingFocus = if (it.isBlank()) emptyList() else listOf(it) },
        modifier = Modifier.fillMaxWidth())
    state.bindingFocus.mapNotNull(layers::get).forEach { layer ->
        val id = layer.source.id.raw
        val value = report.overrides[id] ?: PsdGuideBindings.classification(layer)
        Text(listOf(layer.source.groupPath, layer.source.name).filter { it.isNotBlank() }.joinToString(" / "),
            style = typography.caption, color = colors.textPrimary)
        InspectorFormRow(tr("psdGuide.binding.part")) {
            CompactSelect(SemanticTag.entries, value.tag, ::partLabel, { tag ->
                state.classify(id, value.copy(tag = tag, role = value.role?.takeIf { it.fits(tag) }))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
        InspectorFormRow(tr("psdGuide.binding.sideLabel")) {
            CompactSelect(Side.entries, value.side, ::sideLabel, { side -> state.classify(id, value.copy(side = side)) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun partLabel(tag: SemanticTag) =
    LayerTags.parts[tag]?.joinToString(" · ") { tagLabel(it) } ?: tr("psdGuide.binding.unclassified")

private fun sideLabel(side: Side) = when (side) {
    Side.LEFT -> tagLabel(LayerTags.LEFT)
    Side.RIGHT -> tagLabel(LayerTags.RIGHT)
    Side.NONE -> tr("psdGuide.binding.noSide")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FaceGuide(state: PsdGuideState, modifier: Modifier) {
    val guide = state.guide ?: return
    val candidate = state.preset ?: return
    val turn = remember(candidate) { guide.turn(candidate) }
    if (turn == null) {
        GuideColumns(modifier, preview = { GuideCanvas(state.presetImage) }, controls = { GuideNote(tr("psdGuide.face.unavailable")) })
        return
    }
    val batch = state.batch
    val defaultPose = remember(turn) { (turn.x.default + (turn.x.max - turn.x.default) * .75f) to turn.y.default }
    val pose = state.turnPose ?: defaultPose
    var samples by remember(candidate, batch) { mutableStateOf<Map<Int, WorkspacePsdGuide.StylePreview>?>(null) }
    var images by remember(candidate, batch) { mutableStateOf(emptyMap<Int, ImageBitmap>()) }
    var shownPose by remember(candidate, batch) { mutableStateOf<Pair<Float, Float>?>(null) }
    var firstError by remember(candidate, batch) { mutableStateOf<String?>(null) }
    LaunchedEffect(candidate, batch) {
        samples = coroutineScope {
            batch.mapIndexed { index, option -> async {
                try {
                    index to guide.prepareStyle(candidate, turn, option.values)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (firstError == null) firstError = workspaceFailureMessage(failure)
                    index to null
                }
            } }.awaitAll().mapNotNull { (index, sample) -> sample?.let { index to it } }.toMap()
        }
    }
    val loaded = samples
    LaunchedEffect(loaded) {
        if (loaded != null) snapshotFlow { state.turnPose ?: defaultPose }.collectLatest { (x, y) ->
            images = coroutineScope {
                loaded.map { (index, sample) -> async {
                    try {
                        val frame = sample.render(turn, x, y)
                        index to withContext(Dispatchers.Default) { frame.toImageBitmapFast() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (firstError == null) firstError = workspaceFailureMessage(failure)
                        index to null
                    }
                } }.awaitAll().mapNotNull { (index, bitmap) -> bitmap?.let { index to it } }.toMap()
            }
            shownPose = x to y
        }
    }
    val rendering = shownPose != pose
    val failures = if (shownPose == null) emptySet() else batch.indices.toSet() - images.keys
    val xKeys = remember(turn) { ParameterKeyMarks(listOf(turn.x.min, turn.x.default, turn.x.max), emptyList()) }
    val yKeys = remember(turn) { ParameterKeyMarks(listOf(turn.y.min, turn.y.default, turn.y.max), emptyList()) }
    GuideColumns(modifier, preview = {
        FaceGrid(batch, images, failures, state.selectedStyle, !state.busy) { state.selectedStyle = it }
    }, controls = {
        ParameterPad2D(turn.x, turn.y, { pose.first }, { pose.second }, state.busy, state.busy,
            xKeys, emptyList(), emptyList(), yKeys,
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 224.dp).fillMaxWidth().aspectRatio(1f),
            onChange = { x, y -> state.turnPose = x to y })
        GuideNote(tr("turnExplorer.angle", pose.first, pose.second))
        if (rendering) GuideProgress(tr("turnExplorer.renderingAngles"))
        firstError?.let { GuideNote(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            CompactButton(tr("turnExplorer.next"), { state.newStyles(false) }, enabled = !state.busy)
            CompactButton(tr("turnExplorer.nearby"), { state.newStyles(true) }, enabled = !state.busy && state.selectedStyle != null)
            CompactButton(tr("psdGuide.face.reset"), { state.turnPose = turn.x.default to turn.y.default }, enabled = !state.busy)
            CompactButton(tr("psdGuide.face.use"), state::applyStyle, enabled = !state.busy && !rendering &&
                batch.indexOf(state.selectedStyle) in images && state.selectedStyle != state.appliedStyle, isPrimary = true)
        }
        if (state.appliedStyle != null) {
            GuideNote(tr("psdGuide.face.applied"))
            CompactButton(tr("psdGuide.face.keepPreset"), state::keepPresetStyle, enabled = !state.busy)
        } else GuideNote(tr("psdGuide.face.default"))
    })
}

@Composable
private fun FaceGrid(batch: List<TurnStyleCandidates.Option>, images: Map<Int, ImageBitmap>, failures: Set<Int>,
                     selected: TurnStyleCandidates.Option?, enabled: Boolean, onSelect: (TurnStyleCandidates.Option) -> Unit) {
    val colors = LocalToolColors.current
    val shape = RoundedCornerShape(Corners.Control)
    MenuGlide(Modifier.fillMaxSize().background(colors.inputBackground)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            for (row in 0..2) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                for (column in 0..2) {
                    val index = row * 3 + column
                    val style = batch.getOrNull(index) ?: continue
                    val active = style == selected
                    val image = images[index]
                    val usable = enabled && image != null
                    Box(Modifier.weight(1f).fillMaxHeight().clip(shape)
                        .border(1.dp, if (active) colors.accent else colors.divider, shape)
                        .glideItem(usable)
                        .selectable(active, usable, Role.Button) { onSelect(style) }
                        .handCursor(usable), contentAlignment = Alignment.Center) {
                        if (image != null) Image(image, tr("turnExplorer.option", index + 1), Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit)
                        else if (index in failures) GuideNote(tr("turnExplorer.failed"))
                    }
                }
            }
        }
    }
}
