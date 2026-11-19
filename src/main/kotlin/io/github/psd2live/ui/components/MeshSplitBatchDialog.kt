package io.github.psd2live.ui.components

import io.github.psd2live.ui.utils.toImageBitmapFast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.texture.glideItem

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
    var customSides by mutableStateOf(initialSides())

    private fun initialSides(): List<Side> {
        val sides = MutableList(count) { Side.NONE }
        if (count == 2 && horizontal) {
            components.indices.sortedBy { components[it].centerX }.forEachIndexed { position, component ->
                sides[component] = if (position == 0) Side.RIGHT else Side.LEFT
            }
        }
        return sides
    }

    fun cycleCustomSide(index: Int) {
        if (index !in 0 until count) return
        val next = when (customSides.getOrElse(index) { Side.NONE }) {
            Side.NONE -> Side.LEFT
            Side.LEFT -> Side.RIGHT
            Side.RIGHT -> Side.NONE
        }
        customSides = customSides.toMutableList().also { it[index] = next }
    }

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
            sides[component] = when (mode) {
                SplitNamesMode.LR -> if (position == 0) Side.RIGHT else Side.LEFT
                SplitNamesMode.CUSTOM -> customSides.getOrElse(component) { Side.NONE }
                else -> Side.NONE
            }
        }
        return generated to sides
    }

    val invalidIndices: Set<Int>
        get() {
            if (!isSelected) return emptySet()
            val (names, _) = generatedNamesAndSides()
            val counts = names.groupingBy { it }.eachCount()
            return buildSet {
                names.forEachIndexed { index, name ->
                    if (name.isBlank() || (counts[name] ?: 0) > 1) add(index)
                }
            }
        }

    val isValid: Boolean
        get() = invalidIndices.isEmpty()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SplitHeader(
    items: List<BatchSplitItemState>,
    enabled: Boolean = true,
    showTitle: Boolean = true,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val selectedCount = tr("editor.meshSplit.selectedCount", items.count { it.isSelected }, items.size)
    val singleRowMinWidth = if (showTitle) 480.dp else 280.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= singleRowMinWidth) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showTitle) SectionLabel(tr("start.splits"))
                Text(
                    selectedCount,
                    style = typography.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CompactButton(tr("editor.meshSplit.selectAll"), { items.forEach { it.isSelected = true } }, enabled = enabled, height = 22.dp)
                CompactButton(tr("editor.meshSplit.deselectAll"), { items.forEach { it.isSelected = false } }, enabled = enabled, height = 22.dp)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showTitle) SectionLabel(tr("start.splits"))
                    Text(
                        selectedCount,
                        style = typography.caption,
                        color = colors.textMuted,
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
                ) {
                    CompactButton(tr("editor.meshSplit.selectAll"), { items.forEach { it.isSelected = true } }, enabled = enabled, height = 22.dp)
                    CompactButton(tr("editor.meshSplit.deselectAll"), { items.forEach { it.isSelected = false } }, enabled = enabled, height = 22.dp)
                }
            }
        }
    }
}

/** A layer that can be split: its check, name, how the pieces are named, and each piece with its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SplitCard(
    item: BatchSplitItemState,
    previews: List<androidx.compose.ui.graphics.ImageBitmap>,
    enabled: Boolean = true,
    onFocus: (() -> Unit)? = null,
    focused: Boolean = false,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val count = item.count
    val (generatedNames, _) = item.generatedNamesAndSides()
    val cardShape = RoundedCornerShape(Corners.Panel)
    val thumbShape = RoundedCornerShape(Corners.Control)
    val invalid = item.isSelected && !item.isValid
    val cardClickModifier = onFocus?.let { focus ->
        Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = focus,
            )
            .handCursor(enabled)
    } ?: Modifier

    val cardBackground by animateColorAsState(
        targetValue = when {
            invalid -> colors.error.copy(alpha = 0.08f)
            focused -> colors.selection.copy(alpha = 0.25f)
            !item.isSelected -> colors.inputBackground.copy(alpha = 0.45f)
            else -> colors.panelBackground
        },
        animationSpec = tween(Motion.Instant),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            invalid -> colors.error
            focused -> colors.accent
            item.isSelected && onFocus == null -> colors.accent.copy(alpha = 0.5f)
            else -> colors.divider
        },
        animationSpec = tween(Motion.Instant),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBackground, cardShape)
            .border(BorderStroke(1.dp, borderColor), cardShape)
            .then(cardClickModifier)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactCheckbox(checked = item.isSelected, enabled = enabled, onCheckedChange = { item.isSelected = it; onFocus?.invoke() })
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    item.offer.layerName,
                    style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (item.isSelected) colors.textPrimary else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    tr("editor.meshSplit.componentsCount", count),
                    style = typography.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            if (item.isSelected && onFocus == null) SplitNamingModes(item, enabled, null)
        }

        AnimatedVisibility(
            visible = item.isSelected,
            enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) +
                fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
            exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) +
                fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
        ) {
            val partPalette = listOf(colors.accent, colors.highlight, colors.warning)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onFocus != null) SplitNamingModes(item, enabled, onFocus)

                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.components.indices.forEach { index ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (index < previews.size) {
                                Box(
                                    Modifier.size(54.dp, 40.dp).clip(thumbShape)
                                        .background(colors.inputBackground)
                                        .border(BorderStroke(1.dp, colors.divider), thumbShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = previews[index],
                                        contentDescription = tr("editor.meshSplit.preview", index + 1),
                                        modifier = Modifier.fillMaxSize().padding(2.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            } else if (onFocus != null) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(partPalette[index % partPalette.size]),
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
                                        onFocus?.invoke()
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Gap), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
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
