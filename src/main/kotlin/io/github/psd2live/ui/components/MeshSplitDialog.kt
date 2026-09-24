package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

private enum class SplitNames { LR, TB, NUMBER, CUSTOM }

@Composable
internal fun MeshSplitDialog(
    offer: PSD2LiveViewModel.MeshSplitOffer,
    onSplit: (List<String>, List<Side>) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: (() -> Unit)? = null,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val components = offer.plan.components
    val count = components.size
    val previews = remember(offer) { offer.plan.previewImages.map { it.toComposeImageBitmap() } }
    val horizontal = count == 2 &&
        kotlin.math.abs(components[0].centerX - components[1].centerX) >=
        kotlin.math.abs(components[0].centerY - components[1].centerY)
    var mode by remember(offer) { mutableStateOf(if (count == 2) { if (horizontal) SplitNames.LR else SplitNames.TB } else SplitNames.NUMBER) }
    var custom by remember(offer) { mutableStateOf(List(count) { "${offer.layerName}-${it + 1}" }) }

    val order = when (mode) {
        SplitNames.LR -> components.indices.sortedBy { components[it].centerX }
        SplitNames.TB -> components.indices.sortedBy { components[it].centerY }
        else -> components.indices.toList()
    }
    val suffixes = when (mode) {
        // Match the rig's left/right convention: screen-left is character-right.
        SplitNames.LR -> listOf("r", "l")
        SplitNames.TB -> listOf("t", "b")
        else -> (1..count).map(Int::toString)
    }
    val generated = MutableList(count) { "" }
    val sides = MutableList(count) { Side.NONE }
    order.forEachIndexed { position, component ->
        generated[component] = if (mode == SplitNames.CUSTOM) custom[component].trim()
            else "${offer.layerName}-${suffixes[position]}"
        if (mode == SplitNames.LR) sides[component] = if (position == 0) Side.RIGHT else Side.LEFT
    }
    val valid = generated.all { it.isNotBlank() } && generated.distinct().size == count

    Box(
        Modifier.fillMaxSize().background(Color(0x88000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(8.dp))
                .background(colors.panelElevated)
                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(8.dp))
                .clickable(enabled = false) {}.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr("editor.meshSplit.title"), style = typography.title.copy(fontSize = 15.sp), color = colors.textPrimary)
            Text(tr("editor.meshSplit.body", offer.layerName, count), style = typography.body.copy(fontSize = 13.sp), color = colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val choices = if (count == 2) listOf(
                    SplitNames.LR to "L / R", SplitNames.TB to "T / B",
                    SplitNames.NUMBER to "1 / 2", SplitNames.CUSTOM to tr("editor.meshSplit.custom"),
                ) else listOf(SplitNames.NUMBER to "1…$count", SplitNames.CUSTOM to tr("editor.meshSplit.custom"))
                choices.forEach { (choice, label) ->
                    CompactToggleChip(label, mode == choice, { mode = choice }, showCheckWhenSelected = false)
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                components.indices.forEach { index ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("#${index + 1}", style = typography.caption, color = colors.textMuted, modifier = Modifier.width(30.dp))
                        Box(
                            Modifier.size(84.dp, 58.dp).clip(RoundedCornerShape(4.dp))
                                .background(colors.inputBackground)
                                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = previews[index],
                                contentDescription = tr("editor.meshSplit.preview", index + 1),
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        if (mode == SplitNames.CUSTOM) {
                            CompactTextField(
                                value = custom[index],
                                onValueChange = { value -> custom = custom.toMutableList().also { it[index] = value } },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Text(generated[index], style = typography.body, color = colors.textPrimary)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var autoPromptOnImport by remember { mutableStateOf(AppSettings.autoDetectMeshSplitsOnImport) }
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onDismissAll != null) {
                        CompactButton(tr("editor.meshSplit.keepAll"), onDismissAll)
                    }
                    CompactButton(tr("editor.meshSplit.keep"), onDismiss)
                    CompactButton(tr("editor.meshSplit.confirm"), { onSplit(generated, sides) }, enabled = valid, isPrimary = true)
                }
            }
        }
    }
}
