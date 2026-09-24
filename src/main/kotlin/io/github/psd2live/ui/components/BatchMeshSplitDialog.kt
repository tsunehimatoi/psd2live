package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
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

@Composable
internal fun BatchMeshSplitDialog(
    batchOffer: PSD2LiveViewModel.BatchMeshSplitOffer,
    onSplit: (List<PSD2LiveViewModel.LayerSplitDecision>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val itemStates = remember(batchOffer) {
        batchOffer.offers.map { BatchSplitItemState(it, initialSelected = true) }
    }
    val previewsByOffer = remember(batchOffer) {
        batchOffer.offers.associate { offer ->
            offer.layerId to offer.plan.previewImages.map { it.toComposeImageBitmap() }
        }
    }
    var autoPromptOnImport by remember { mutableStateOf(AppSettings.autoDetectMeshSplitsOnImport) }

    val selectedItems = itemStates.filter { it.isSelected }
    val selectedCount = selectedItems.size
    val allValid = selectedItems.all { it.isValid }

    Box(
        Modifier.fillMaxSize().background(Color(0x88000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(8.dp))
                .background(colors.panelElevated)
                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(8.dp))
                .clickable(enabled = false) {}.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        tr("editor.meshSplit.batchTitle"),
                        style = typography.title.copy(fontSize = 15.sp),
                        color = colors.textPrimary,
                    )
                    Text(
                        tr("editor.meshSplit.batchBody", batchOffer.offers.size),
                        style = typography.body.copy(fontSize = 13.sp),
                        color = colors.textMuted,
                    )
                }
            }

            // Quick select toolbar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(
                        text = tr("editor.meshSplit.selectAll"),
                        onClick = { itemStates.forEach { it.isSelected = true } },
                    )
                    CompactButton(
                        text = tr("editor.meshSplit.deselectAll"),
                        onClick = { itemStates.forEach { it.isSelected = false } },
                    )
                }
                Text(
                    tr("editor.meshSplit.selectedCount", selectedCount, itemStates.size),
                    style = typography.caption,
                    color = colors.textMuted,
                )
            }

            // Scrollable list of layer split cards
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemStates.forEach { item ->
                    val offer = item.offer
                    val count = item.count
                    val previews = previewsByOffer[offer.layerId].orEmpty()
                    val (generatedNames, _) = item.generatedNamesAndSides()

                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (item.isSelected) colors.panelBackground else colors.inputBackground.copy(alpha = 0.5f))
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (item.isSelected) colors.accent.copy(alpha = 0.5f) else colors.divider,
                                ),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Title + Checkbox + Mode chips
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CompactCheckbox(
                                checked = item.isSelected,
                                onCheckedChange = { item.isSelected = it },
                            )
                            Text(
                                offer.layerName,
                                style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                                color = if (item.isSelected) colors.textPrimary else colors.textMuted,
                            )
                            Text(
                                tr("editor.meshSplit.componentsCount", count),
                                style = typography.caption,
                                color = colors.textMuted,
                            )
                            Spacer(Modifier.weight(1f))
                            if (item.isSelected) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val choices = if (count == 2) listOf(
                                        SplitNamesMode.LR to "L / R",
                                        SplitNamesMode.TB to "T / B",
                                        SplitNamesMode.NUMBER to "1 / 2",
                                        SplitNamesMode.CUSTOM to tr("editor.meshSplit.custom"),
                                    ) else listOf(
                                        SplitNamesMode.NUMBER to "1…$count",
                                        SplitNamesMode.CUSTOM to tr("editor.meshSplit.custom"),
                                    )
                                    choices.forEach { (choice, label) ->
                                        CompactToggleChip(
                                            text = label,
                                            selected = item.mode == choice,
                                            onToggle = { item.mode = choice },
                                            showCheckWhenSelected = false,
                                        )
                                    }
                                }
                            }
                        }

                        // Component preview thumbnails row
                        if (item.isSelected) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                item.components.indices.forEach { index ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(4.dp))
                                                .background(colors.inputBackground)
                                                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (index < previews.size) {
                                                Image(
                                                    bitmap = previews[index],
                                                    contentDescription = tr("editor.meshSplit.preview", index + 1),
                                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                                    contentScale = ContentScale.Fit,
                                                )
                                            }
                                        }
                                        if (item.mode == SplitNamesMode.CUSTOM) {
                                            CompactTextField(
                                                value = item.customNames.getOrElse(index) { "" },
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
            }

            // Footer
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Bottom left: auto-detect setting
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable {
                        autoPromptOnImport = !autoPromptOnImport
                        AppSettings.autoDetectMeshSplitsOnImport = autoPromptOnImport
                    },
                ) {
                    CompactCheckbox(
                        checked = autoPromptOnImport,
                        onCheckedChange = {
                            autoPromptOnImport = it
                            AppSettings.autoDetectMeshSplitsOnImport = it
                        },
                    )
                    Text(
                        tr("editor.meshSplit.promptOnImport"),
                        style = typography.caption,
                        color = colors.textMuted,
                    )
                }

                // Bottom right: actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(
                        text = tr("editor.meshSplit.keepAll"),
                        onClick = onDismiss,
                    )
                    CompactButton(
                        text = tr("editor.meshSplit.confirmSelected", selectedCount),
                        onClick = {
                            val decisions = selectedItems.map { item ->
                                val (names, sides) = item.generatedNamesAndSides()
                                PSD2LiveViewModel.LayerSplitDecision(item.offer, names, sides)
                            }
                            onSplit(decisions)
                        },
                        enabled = selectedCount > 0 && allValid,
                        isPrimary = true,
                    )
                }
            }
        }
    }
}
