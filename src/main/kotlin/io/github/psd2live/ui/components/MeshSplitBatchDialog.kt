package io.github.psd2live.ui.components

import io.github.psd2live.ui.utils.toImageBitmapFast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppPrompt
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

internal enum class SplitNamesMode { LR, TB, NUMBER, CUSTOM }

internal class BatchSplitItemState(
    val offer: PSD2LiveViewModel.MeshSplitOffer,
    initialSelected: Boolean = true,
) {
    var isSelected by mutableStateOf(initialSelected)
    val components = offer.plan.components
    val count = components.size
    val horizontal = count == 2 &&
        kotlin.math.abs(components[0].centerX - components[1].centerX) >=
        kotlin.math.abs(components[0].centerY - components[1].centerY)
    var mode by mutableStateOf(if (count == 2) { if (horizontal) SplitNamesMode.LR else SplitNamesMode.TB } else SplitNamesMode.NUMBER)
    var customNames by mutableStateOf(List(count) { "${offer.layerName}-${it + 1}" })

    fun generatedNamesAndSides(): Pair<List<String>, List<Side>> {
        val order = when (mode) {
            SplitNamesMode.LR -> components.indices.sortedBy { components[it].centerX }
            SplitNamesMode.TB -> components.indices.sortedBy { components[it].centerY }
            else -> components.indices.toList()
        }
        val suffixes = when (mode) {
            SplitNamesMode.LR -> listOf("r", "l")
            SplitNamesMode.TB -> listOf("t", "b")
            else -> (1..count).map(Int::toString)
        }
        val generated = MutableList(count) { "" }
        val sides = MutableList(count) { Side.NONE }
        order.forEachIndexed { position, component ->
            generated[component] = if (mode == SplitNamesMode.CUSTOM) customNames[component].trim()
                else "${offer.layerName}-${suffixes[position]}"
            if (mode == SplitNamesMode.LR) sides[component] = if (position == 0) Side.RIGHT else Side.LEFT
        }
        return generated to sides
    }

    val isValid: Boolean
        get() {
            if (!isSelected) return true
            val (names, _) = generatedNamesAndSides()
            return names.all { it.isNotBlank() } && names.distinct().size == count
        }
}

/**
 * The layers whose mesh falls apart into pieces, each to be split or kept: after a layer import that adds several,
 * or from Tools. [onApply] gets the splits chosen.
 */
@Composable
internal fun MeshSplitBatchDialog(
    offer: PSD2LiveViewModel.MeshSplitBatchOffer,
    onApply: (List<PSD2LiveViewModel.LayerSplitDecision>) -> Unit,
    onDismiss: () -> Unit,
    mutedOnImport: Boolean = !AppSettings.autoDetectMeshSplitsOnImport,
    onPromptMutedChange: (AppPrompt, Boolean) -> Unit = { prompt, muted -> AppSettings.setPromptEnabled(prompt, !muted) },
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val itemStates = remember(offer) { offer.splits.map { BatchSplitItemState(it) } }
    val previewsByOffer = remember(offer) {
        offer.splits.associate { split -> split.layerId to split.plan.previewImages.map { it.toImageBitmapFast() } }
    }
    val selectedItems = itemStates.filter { it.isSelected }
    val canApply = selectedItems.isNotEmpty() && selectedItems.all { it.isValid }

    fun decisions() = selectedItems.map { item ->
        val (names, sides) = item.generatedNamesAndSides()
        PSD2LiveViewModel.LayerSplitDecision(item.offer, names, sides)
    }

    val source = offer.preview.analysis.source
    ModalDialogFrame(
        title = tr("editor.meshSplit.batchTitle"),
        subtitle = tr("editor.meshSplit.batchBody", offer.splits.size),
        onDismiss = onDismiss,
        width = 720.dp,
        maxHeight = 620.dp,
        scrollable = false,
        headerDivider = true,
        bodyPadding = PaddingValues(0.dp),
        bodySpacing = 0.dp,
        titleTrailing = {
            Text(
                tr("start.subtitle", source.widthPx.toString(), source.heightPx.toString(), offer.preview.analysis.layers.size),
                style = typography.caption,
                color = colors.textMuted,
            )
        },
        footerStart = if (offer.splits.first().fromImport) {
            {
                DontShowAgainCheckbox(AppPrompt.MESH_SPLITS_ON_IMPORT, mutedOnImport, onPromptMutedChange,
                    label = tr("prompt.dontShowOnImport"))
            }
        } else null,
        footer = {
            CompactButton(text = tr("editor.meshSplit.keepAll"), onClick = onDismiss)
            CompactButton(
                text = tr("editor.meshSplit.confirmSelected", selectedItems.size),
                onClick = { onApply(decisions()) },
                enabled = canApply,
                isPrimary = true,
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SplitHeader(itemStates)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemStates.forEach { SplitCard(it, previewsByOffer[it.offer.layerId].orEmpty()) }
            }
        }
    }
}

@Composable
internal fun SplitHeader(items: List<BatchSplitItemState>, enabled: Boolean = true) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(tr("start.splits"))
        Text(
            tr("editor.meshSplit.selectedCount", items.count { it.isSelected }, items.size),
            style = typography.caption,
            color = colors.textMuted,
        )
        Spacer(Modifier.weight(1f))
        CompactButton(tr("editor.meshSplit.selectAll"), { items.forEach { it.isSelected = true } }, enabled = enabled, height = 22.dp)
        CompactButton(tr("editor.meshSplit.deselectAll"), { items.forEach { it.isSelected = false } }, enabled = enabled, height = 22.dp)
    }
}

/** A layer that can be split: its check, name, how the pieces are named, and each piece with its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SplitCard(item: BatchSplitItemState, previews: List<androidx.compose.ui.graphics.ImageBitmap>,
                       enabled: Boolean = true, onFocus: (() -> Unit)? = null, focused: Boolean = false) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val count = item.count
    val (generatedNames, _) = item.generatedNamesAndSides()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (item.isSelected) colors.panelBackground else colors.inputBackground.copy(alpha = 0.5f))
            .border(
                BorderStroke(1.dp, if (focused || item.isSelected && onFocus == null) colors.accent.copy(alpha = 0.5f) else colors.divider),
                RoundedCornerShape(6.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactCheckbox(checked = item.isSelected, enabled = enabled, onCheckedChange = { item.isSelected = it; onFocus?.invoke() })
            Text(
                item.offer.layerName,
                style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (item.isSelected) colors.textPrimary else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).then(
                    onFocus?.let { Modifier.clickable(enabled = enabled, onClick = it) } ?: Modifier),
            )
            Text(tr("editor.meshSplit.componentsCount", count), style = typography.caption, color = colors.textMuted)
            Spacer(Modifier.weight(1f))
            if (item.isSelected && onFocus == null) SplitNamingModes(item, enabled, null)
        }
        if (item.isSelected && onFocus != null) SplitNamingModes(item, enabled, onFocus)

        if (item.isSelected) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.components.indices.forEach { index ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index < previews.size) Box(
                            Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(4.dp))
                                .background(colors.inputBackground)
                                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                    bitmap = previews[index],
                                    contentDescription = tr("editor.meshSplit.preview", index + 1),
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                    contentScale = ContentScale.Fit,
                            )
                        }
                        if (item.mode == SplitNamesMode.CUSTOM) {
                            CompactTextField(
                                value = item.customNames.getOrElse(index) { "" },
                                enabled = enabled,
                                onValueChange = { value ->
                                    item.customNames = item.customNames.toMutableList().also {
                                        if (index < it.size) it[index] = value
                                    }
                                },
                                modifier = Modifier.width(100.dp),
                            )
                        } else {
                            Text(
                                generatedNames.getOrElse(index) { "" },
                                style = typography.caption.copy(fontSize = 11.sp),
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitNamingModes(item: BatchSplitItemState, enabled: Boolean, onFocus: (() -> Unit)?) {
    val count = item.count
    val choices = if (count == 2) listOf(
        SplitNamesMode.LR to "L / R",
        SplitNamesMode.TB to "T / B",
        SplitNamesMode.NUMBER to "1 / 2",
        SplitNamesMode.CUSTOM to tr("editor.meshSplit.custom"),
    ) else listOf(
        SplitNamesMode.NUMBER to "1…$count",
        SplitNamesMode.CUSTOM to tr("editor.meshSplit.custom"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { (choice, label) ->
            CompactToggleChip(text = label, selected = item.mode == choice,
                onToggle = { item.mode = choice; onFocus?.invoke() }, enabled = enabled, showCheckWhenSelected = false)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalToolColors.current
    Text(
        text,
        style = LocalToolTypography.current.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
