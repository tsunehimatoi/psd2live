package io.github.psd2live.ui.views

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.BatchSplitItemState
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.components.SplitNamesMode
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.utils.toImageBitmapFast
import io.github.psd2live.ui.views.texture.GlidingSegments
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem

/**
 * High-efficiency inline list for the Split step:
 * every layer keeps checkbox, thumbnails, name, and naming controls directly accessible in 1 row
 * (wrapping cleanly to 2 rows when narrow). Clicking an item focuses it on canvas; clicking again
 * or clicking empty space clears selection.
 */
@Composable
internal fun SplitGuideControls(state: PsdGuideState) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val items = state.splitItems
    val enabled = !state.busy
    val applied = state.source !== state.guide?.base

    DisposableEffect(Unit) {
        onDispose { state.splitHoveredComponent = null }
    }

    if (!state.splitScanned) {
        GuideNote(tr("psdGuide.step.split.desc"))
        GuideInfoBanner(tr("psdGuide.split.optional"), GuideBannerTone.INFO)
        CompactButton(
            text = tr("psdGuide.split.scan"),
            onClick = state::scan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    if (items.isEmpty()) {
        GuideNote(tr("psdGuide.step.split.desc"))
        GuideInfoBanner(
            text = tr(if (applied) "psdGuide.split.applied" else "psdGuide.split.none"),
            tone = GuideBannerTone.SUCCESS,
        )
        if (applied) {
            CompactButton(
                text = tr("psdGuide.split.reset"),
                onClick = state::resetSplits,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    val groupPathById = remember(state.source, items) {
        val layers = state.source?.model?.analysis?.layers.orEmpty()
        layers.associate { it.source.id.raw to it.source.groupPath }
    }
    var query by remember(state.source) { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val showSearch = items.size > 8 || query.isNotBlank()
    val filteredItems = remember(items, groupPathById, trimmedQuery) {
        if (trimmedQuery.isEmpty()) items
        else items.filter { item ->
            val group = groupPathById[item.offer.layerId].orEmpty()
            item.offer.layerName.contains(trimmedQuery, ignoreCase = true) ||
                group.contains(trimmedQuery, ignoreCase = true)
        }
    }

    val selectedCount = items.count { it.isSelected }
    val listState = rememberLazyListState()

    LaunchedEffect(state.splitFocus, filteredItems) {
        val focusId = state.splitFocus ?: return@LaunchedEffect
        val targetIndex = filteredItems.indexOfFirst { it.offer.layerId == focusId }
        if (targetIndex >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val start = listState.layoutInfo.viewportStartOffset
            val end = listState.layoutInfo.viewportEndOffset
            val alreadyVisible = visible.any { it.index == targetIndex && it.offset >= start && it.offset + it.size <= end }
            if (!alreadyVisible) listState.scrollToItem(targetIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GuideNote(tr("psdGuide.step.split.desc"))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
        ) {
            Text(
                text = tr("editor.meshSplit.selectedCount", selectedCount, items.size),
                style = typography.caption,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CompactButton(
                text = tr("editor.meshSplit.selectAll"),
                onClick = { items.forEach { it.isSelected = true } },
                enabled = enabled && selectedCount < items.size,
                height = 20.dp,
            )
            CompactButton(
                text = tr("editor.meshSplit.deselectAll"),
                onClick = { items.forEach { it.isSelected = false } },
                enabled = enabled && selectedCount > 0,
                height = 20.dp,
            )
        }

        if (showSearch) {
            CompactTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("psdGuide.split.search"),
                enabled = enabled,
                leadingIcon = { IconSearch(Modifier.size(12.dp), tint = colors.textMuted) },
                trailingIcon = if (query.isEmpty()) null else {
                    {
                        CompactIconButton(
                            onClick = { query = "" },
                            enabled = enabled,
                            tooltip = tr("parameters.clearSearch"),
                            size = 16.dp,
                        ) {
                            IconClose(Modifier.size(8.dp), tint = colors.textMuted)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                height = 22.dp,
            )
        }

        val listShape = RoundedCornerShape(Corners.Control)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(listShape)
                .background(colors.panelElevated, listShape)
                .border(0.5.dp, colors.divider, listShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled && state.splitFocus != null,
                    onClick = { state.showSplitPreview(null) },
                )
                .padding(3.dp),
        ) {
            MenuGlide(Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = 1.dp, bottom = 1.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (filteredItems.isEmpty()) {
                        item("empty-split") {
                            GuideNote(
                                text = "0 / ${items.size}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    } else {
                        items(filteredItems, key = { it.offer.layerId }) { item ->
                            val id = item.offer.layerId
                            val focused = state.splitFocus == id
                            SplitInlineRow(
                                item = item,
                                groupPath = groupPathById[id].orEmpty(),
                                focused = focused,
                                enabled = enabled,
                                onToggleFocus = {
                                    state.showSplitPreview(if (focused) null else id)
                                },
                                onHoverComponent = { compIdx ->
                                    state.splitHoveredComponent = compIdx?.let { id to it }
                                },
                            )
                        }
                    }
                }
            }
            if (listState.canScrollForward || listState.canScrollBackward) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SplitInlineRow(
    item: BatchSplitItemState,
    groupPath: String,
    focused: Boolean,
    enabled: Boolean,
    onToggleFocus: () -> Unit,
    onHoverComponent: (Int?) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val palette = splitPartPalette(colors)
    val thumbs: List<ImageBitmap> = remember(item.offer.layerId) {
        item.offer.plan.previewImages.map { it.toImageBitmapFast() }
    }
    val invalidIndices = item.invalidIndices
    val invalid = invalidIndices.isNotEmpty()
    val rowShape = RoundedCornerShape(Corners.Control)
    val rowBg by animateColorAsState(
        targetValue = when {
            invalid && focused -> colors.error.copy(alpha = 0.16f)
            focused -> colors.selection
            else -> Color.Transparent
        },
        animationSpec = tween(Motion.Instant),
    )

    val modes = remember(item.count) {
        if (item.count == 2) listOf(
            SplitNamesMode.LR,
            SplitNamesMode.TB,
            SplitNamesMode.NUMBER,
            SplitNamesMode.CUSTOM,
        ) else listOf(
            SplitNamesMode.NUMBER,
            SplitNamesMode.CUSTOM,
        )
    }

    @Composable
    fun NamingControl(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(Corners.Control))
                .background(colors.inputBackground)
                .border(0.5.dp, colors.divider, RoundedCornerShape(Corners.Control))
                .padding(1.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            GlidingSegments(
                choices = modes,
                selected = item.mode,
                onSelect = { item.mode = it },
                label = { mode ->
                    when (mode) {
                        SplitNamesMode.LR -> "L/R"
                        SplitNamesMode.TB -> "T/B"
                        SplitNamesMode.NUMBER -> if (item.count == 2) "1/2" else "1..${item.count}"
                        SplitNamesMode.CUSTOM -> tr("editor.meshSplit.custom")
                    }
                },
                enabled = enabled,
                modifier = Modifier.handCursor(enabled),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glideItem(enabled && !focused)
            .clip(rowShape)
            .background(rowBg, rowShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onToggleFocus,
            )
            .handCursor(enabled)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = true),
            ) {
                CompactCheckbox(
                    checked = item.isSelected,
                    enabled = enabled,
                    onCheckedChange = { item.isSelected = it },
                )

                // Compact inline component thumbnails with matching tint borders.
                if (thumbs.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val thumbShape = RoundedCornerShape(3.dp)
                        thumbs.take(3).forEachIndexed { idx, bitmap ->
                            val tint = if (item.isSelected) palette[idx % palette.size] else colors.textMuted
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(thumbShape)
                                    .border(0.75.dp, tint.copy(alpha = if (item.isSelected) 0.75f else 0.35f), thumbShape)
                                    .onPointerEvent(PointerEventType.Enter) { if (item.isSelected) onHoverComponent(idx) }
                                    .onPointerEvent(PointerEventType.Exit) { onHoverComponent(null) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawGuideChecker(
                                        light = colors.checkerLight,
                                        dark = colors.checkerDark,
                                        cell = 4.dp.toPx(),
                                    )
                                }
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = tr("editor.meshSplit.preview", idx + 1),
                                    modifier = Modifier.fillMaxSize().padding(1.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.offer.layerName,
                            style = typography.body.copy(
                                fontSize = 11.5.sp,
                                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = when {
                                focused -> colors.selectionText
                                item.isSelected -> colors.textPrimary
                                else -> colors.textMuted
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (invalid) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(colors.error))
                        }
                    }
                    if (groupPath.isNotBlank()) {
                        Text(
                            text = groupPath,
                            style = typography.captionSmall,
                            color = if (focused) colors.selectionText.copy(alpha = 0.78f) else colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (item.isSelected) {
                NamingControl()
            } else {
                Text(
                    text = tr("psdGuide.split.keepShort"),
                    style = typography.captionSmall,
                    color = if (focused) colors.selectionText.copy(alpha = 0.75f) else colors.textDisabled,
                    maxLines = 1,
                )
            }
        }

        // Only expand custom name inputs when Custom mode is active.
        AnimatedVisibility(
            visible = item.isSelected && item.mode == SplitNamesMode.CUSTOM,
            enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) +
                fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
            exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) +
                fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
        ) {
            val (_, generatedSides) = item.generatedNamesAndSides()
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item.components.indices.forEach { index ->
                    val partColor = if (index in invalidIndices) colors.error else palette[index % palette.size]
                    val sideText = splitSideShortLabel(generatedSides.getOrElse(index) { Side.NONE })
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPointerEvent(PointerEventType.Enter) { onHoverComponent(index) }
                            .onPointerEvent(PointerEventType.Exit) { onHoverComponent(null) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(partColor))
                        CompactTextField(
                            value = item.customNames.getOrElse(index) { "" },
                            enabled = enabled,
                            onValueChange = { value ->
                                item.customNames = item.customNames.toMutableList().also {
                                    if (index < it.size) it[index] = value
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (index in invalidIndices) {
                                        Modifier.border(1.dp, colors.error, RoundedCornerShape(Corners.Control))
                                    } else Modifier,
                                ),
                            height = 22.dp,
                        )
                        val pillBg = if (sideText != null) colors.accent.copy(alpha = 0.14f) else colors.controlBackground
                        val pillFg = if (sideText != null) colors.accent else colors.textMuted
                        GuideBadge(
                            text = sideText ?: "—",
                            color = pillFg,
                            background = pillBg,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = enabled,
                                    onClick = { item.cycleCustomSide(index) },
                                )
                                .handCursor(enabled),
                        )
                    }
                }
                if (invalid) {
                    Text(
                        text = tr("psdGuide.split.invalidNames"),
                        style = typography.caption,
                        color = colors.error,
                    )
                }
            }
        }
    }
}
